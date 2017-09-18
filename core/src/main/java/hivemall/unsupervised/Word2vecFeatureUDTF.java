/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package hivemall.unsupervised;

import hivemall.UDTFWithOptions;
import hivemall.utils.collections.maps.Int2FloatOpenHashTable;
import hivemall.utils.collections.maps.Int2IntOpenHashTable;
import hivemall.utils.hadoop.HiveUtils;
import hivemall.utils.lang.Primitives;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.serde2.objectinspector.*;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorUtils;

import javax.annotation.Nonnull;

import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import java.util.*;

public abstract class Word2vecBaseUDTF extends UDTFWithOptions {
    protected transient AbstractWord2vecModel model;

    // skip-gram parameters
    protected int win;
    protected int neg;
    protected Int2FloatOpenHashTable S;
    protected Int2IntOpenHashTable A;
    protected Int2FloatOpenHashTable discardTable;

    private Random rnd;

    private int previousNegativeSamplerId;
    private Map<String, Integer> word2index;

    private ListObjectInspector docOI;
    private PrimitiveObjectInspector wordOI;

    private ListObjectInspector negativeTableOI;
    private ListObjectInspector negativeTableElementListOI;
    private PrimitiveObjectInspector negativeTableElementOI;

    private MapObjectInspector discardTableOI;
    private PrimitiveObjectInspector discardTableKeyOI;
    private PrimitiveObjectInspector discardTableValueOI;

    private PrimitiveObjectInspector splitIdOI;

    @Override
    public StructObjectInspector initialize(ObjectInspector[] argOIs) throws UDFArgumentException {
        final int numArgs = argOIs.length;

        if (numArgs != 5 && numArgs != 6) {
            throw new UDFArgumentException(getClass().getSimpleName()
                    + " takes 5 or 6 arguments:  [, constant string options]: "
                    + Arrays.toString(argOIs));
        }

        this.docOI = HiveUtils.asListOI(argOIs[0]);
        wordOI = HiveUtils.asStringOI(docOI.getListElementObjectInspector());

        this.splitIdOI = HiveUtils.asIntCompatibleOI(argOIs[1]);
        this.negativeTableOI = HiveUtils.asListOI(argOIs[2]);
        this.negativeTableElementListOI = HiveUtils.asListOI(negativeTableOI.getListElementObjectInspector());
        this.negativeTableElementOI = HiveUtils.asStringOI(negativeTableElementListOI.getListElementObjectInspector());

        this.discardTableOI = HiveUtils.asMapOI(argOIs[3]);
        this.discardTableKeyOI = HiveUtils.asStringOI(discardTableOI.getMapKeyObjectInspector());
        this.discardTableValueOI = HiveUtils.asFloatingPointOI(discardTableOI.getMapValueObjectInspector());

        this.numTrainWordsOI = HiveUtils.asLongCompatibleOI(argOIs[4]);

        processOptions(argOIs);

        List<String> fieldNames = new ArrayList<>();
        List<ObjectInspector> fieldOIs = new ArrayList<>();

        fieldNames.add("inWord");
        fieldNames.add("pos");
        fieldNames.add("neg");

        fieldOIs.add(PrimitiveObjectInspectorFactory.writableStringObjectInspector);
        fieldOIs.add(PrimitiveObjectInspectorFactory.writableStringObjectInspector);
//        fieldOIs.add(PrimitiveObjectInspectorFactory.writableIntObjectInspector);
        // list ?

        this.previousNegativeSamplerId = -1;
        this.rnd = new Random();

        return ObjectInspectorFactory.getStandardStructObjectInspector(fieldNames, fieldOIs);
    }

    @Override
    protected Options getOptions() {
        Options opts = new Options();
        opts.addOption("win", "window", true, "Range for context word [default: 5]");
        opts.addOption("neg", "negative", true, "The number of negative sampled words per word [default: 5]");
        return opts;
    }

    @Override
    protected CommandLine processOptions(ObjectInspector[] argOIs) throws UDFArgumentException {
        CommandLine cl = null;

        int win = 5;
        int neg = 5;

        if (argOIs.length >= 6) {
            String rawArgs = HiveUtils.getConstString(argOIs[5]);
            cl = parseOptions(rawArgs);

            win = Primitives.parseInt(cl.getOptionValue("win"), win);
            if (win <= 0.d) {
                throw new UDFArgumentException("Argument `int win` must be positive: " + win);
            }

            neg = Primitives.parseInt(cl.getOptionValue("neg"), neg);
            if (neg < 0.d) {
                throw new UDFArgumentException("Argument `int neg` must be non-negative: " + neg);
            }
        }

        this.win = win;
        this.neg = neg;
        return cl;
    }

    @Override
    public void process(Object[] args) throws HiveException {
        int negativeSamplerId = PrimitiveObjectInspectorUtils.getInt(args[1], this.splitIdOI);
        if (previousNegativeSamplerId != negativeSamplerId) {
            word2index = new HashMap<>();
            parseNegativeTable(args[2]);
            parseDiscardTable(args[3]);
            numTrainWords = PrimitiveObjectInspectorUtils.getLong(args[4], this.numTrainWordsOI);

            // TODO reset or keep? (also alias table and word2index)
            previousNegativeSamplerId = negativeSamplerId;
        }

        // parse document
        List<Integer> doc = new ArrayList<>();
        int docLength = docOI.getListLength(args[0]);
        wordCount += (long) docLength;
        for (int i = 0; i < docLength; i++) {
            String word = PrimitiveObjectInspectorUtils.getString(docOI.getListElement(args[0], i),
                wordOI);
            int wordId;
            if (!word2index.containsKey(word)) {
                wordId = word2index.size();
                word2index.put(word, wordId);
            } else {
                wordId = word2index.get(word);
            }

            // discard
            if (discardTable.get(wordId) < rnd.nextFloat()) {
                continue;
            }
            doc.add(wordId);
        }

        this.model.iteration(doc, currentLR);
    }

    private void createRecort() {

        int docLength = doc.size();
        for (int inputWordPosition = 0; inputWordPosition < docLength; inputWordPosition++) {
            int w = doc.get(inputWordPosition);
            int windowSize = rnd.nextInt(win);

            for (int contextPosition = inputWordPosition - windowSize; contextPosition < inputWordPosition
                    + windowSize + 1; contextPosition++) {
                if (contextPosition == inputWordPosition)
                    continue;
                if (contextPosition < 0)
                    continue;
                if (contextPosition >= docLength)
                    continue;
                int c = doc.get(contextPosition);

                int target;
                for (int d = 0; d < neg + 1; d++) {
                    if (d == 0) {
                        target = c;
                    } else {
                        target = negativeSample(c);
                    }
                }
            }
        }
    }

    private void parseNegativeTable(Object listObj) {
        int aliasSize = negativeTableOI.getListLength(listObj);
        Int2FloatOpenHashTable S = new Int2FloatOpenHashTable(aliasSize);
        Int2IntOpenHashTable A = new Int2IntOpenHashTable(aliasSize);

        // to avoid conflicting aliasBin[2]'s word
        for (int wordId = 0; wordId < aliasSize; wordId++) {
            List<?> aliasBin = negativeTableElementListOI.getList(negativeTableOI.getListElement(
                listObj, wordId));
            String word = PrimitiveObjectInspectorUtils.getString(aliasBin.get(0),
                negativeTableElementOI);

            word2index.put(word, wordId);
            S.put(wordId, Float.parseFloat(PrimitiveObjectInspectorUtils.getString(aliasBin.get(1),
                negativeTableElementOI)));
        }

        for (int i = 0; i < aliasSize; i++) {
            List<?> aliasBin = negativeTableElementListOI.getList(negativeTableOI.getListElement(
                listObj, i));
            String word = PrimitiveObjectInspectorUtils.getString(aliasBin.get(2),
                negativeTableElementOI);

            int wordId = word2index.size();
            word2index.put(word, wordId);

            A.put(i, wordId);
        }
        this.S = S;
        this.A = A;
    }

    private void parseDiscardTable(Object mapObj) {
        Int2FloatOpenHashTable discard = new Int2FloatOpenHashTable(
            this.discardTableOI.getMapSize(mapObj));
        discard.defaultReturnValue(1.f);

        for (Map.Entry<?, ?> entry : this.discardTableOI.getMap(mapObj).entrySet()) {
            String word = PrimitiveObjectInspectorUtils.getString(entry.getKey(),
                this.discardTableKeyOI);
            float p = PrimitiveObjectInspectorUtils.getFloat(entry.getValue(),
                this.discardTableValueOI);

            int wordId;
            if (!word2index.containsKey(word)) {
                wordId = word2index.size();
                word2index.put(word, wordId);
            } else {
                wordId = word2index.get(word);
            }

            discard.put(wordId, p);
        }
        this.discardTable = discard;
    }


    public void close() throws HiveException {
    }
}
