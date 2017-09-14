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

import hivemall.utils.collections.maps.Int2DoubleOpenHashTable;
import hivemall.utils.collections.maps.Int2FloatOpenHashTable;
import hivemall.utils.collections.maps.Int2IntOpenHashTable;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Random;

public abstract class AbstractWord2vecModel {
    protected final int maxSigmoid = 6;
    protected final int sigmoidTableSize = 1000;
    protected long numTrainWords;
    protected int dim;
    protected int win;
    protected int neg;
    protected Int2DoubleOpenHashTable S;
    protected Int2IntOpenHashTable A;
    protected Random rnd;
    protected Int2FloatOpenHashTable sigmoidTable;
    protected Int2FloatOpenHashTable contextWeights;
    protected Int2FloatOpenHashTable inputWeights;

    public AbstractWord2vecModel(int dim, int win, int neg, long numTrainWords,
            Int2DoubleOpenHashTable S, Int2IntOpenHashTable A) {
        this.dim = dim;
        this.win = win;
        this.neg = neg;
        this.numTrainWords = numTrainWords;
        this.A = A;
        this.S = S;

        rnd = new Random();

        this.sigmoidTable = initSigmoidTable(maxSigmoid, sigmoidTableSize);

        // TODO how to estimate size
        this.inputWeights = new Int2FloatOpenHashTable(10578*dim);
        // for small corpus, some word vector values are 0.
        // so it skip is one choice,
        inputWeights.defaultReturnValue(0.f);
        this.contextWeights = new Int2FloatOpenHashTable(10578*dim);
        contextWeights.defaultReturnValue(0.f);
    }

    protected static Int2FloatOpenHashTable initSigmoidTable(double maxSigmoid, int sigmoidTableSize) {
        Int2FloatOpenHashTable sigmoidTable = new Int2FloatOpenHashTable(sigmoidTableSize);
        for (int i = 0; i < sigmoidTableSize; i++) {
            float x = ((float) i / sigmoidTableSize * 2 - 1) * (float) maxSigmoid;
            sigmoidTable.put(i, 1.f / ((float) Math.exp(-x) + 1.f));
        }
        return sigmoidTable;
    }

    protected int negativeSample(final int excludeWordId) {
        int result;
        do {
            int k = this.rnd.nextInt(this.A.size());

            if (S.get(k) > this.rnd.nextDouble()) {
                result = k;
            } else {
                result = A.get(k);
            }
        } while (result == excludeWordId);
        return result;
    }

    protected float grad(int label, int w, int c) {
        if (!inputWeights.containsKey(w)) {
            for (int i = 0; i < dim; i++) {
                inputWeights.put(w * dim + i, (this.rnd.nextFloat() - 0.5f) / this.dim);
            }
        }

        float dotValue = 0.f;
        for (int i = 0; i < dim; i++) {
            dotValue += inputWeights.get(w * dim + i) * contextWeights.get(c * dim + i);
        }

        return (label - sigmoid(dotValue));
    }

    private float sigmoid(float v) {
        if (v > maxSigmoid) {
            return 1.f;
        } else if (v < -maxSigmoid) {
            return 0.f;
        } else {
            return sigmoidTable.get((int) ((v + maxSigmoid) * (sigmoidTableSize / maxSigmoid / 2)));
        }
    }

    protected abstract void iteration(@Nonnull final List<Integer> doc, @Nonnull final float lr);
}
