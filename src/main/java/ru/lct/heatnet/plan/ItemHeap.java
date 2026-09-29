package ru.lct.heatnet.plan;

import java.util.Arrays;

/**
 * Min-heap of search items on primitive arrays (the router pushes up to vertices × expansions items;
 * object queues with comparators dominated the profile). Items are never removed from storage, only
 * from the heap; {@link #reset()} reuses the arrays.
 */
final class ItemHeap {

    double[] key = new double[1024];
    double[] g = new double[1024];
    int[] node = new int[1024];
    int[] from = new int[1024];
    Object[] tie = new Object[1024];
    Object[] edge = new Object[1024];
    private int[] heap = new int[1024];
    private int size;
    private int items;

    void reset() {
        size = 0;
        Arrays.fill(tie, 0, items, null);
        Arrays.fill(edge, 0, items, null);
        items = 0;
    }

    /** Items pushed since the last reset (popped ones included). */
    int items() {
        return items;
    }

    boolean isEmpty() {
        return size == 0;
    }

    void push(double k, int nodeId, int fromId, Object tieOption, Object edgeEval, double gValue) {
        if (items == key.length) {
            int n = items * 2;
            key = Arrays.copyOf(key, n);
            g = Arrays.copyOf(g, n);
            node = Arrays.copyOf(node, n);
            from = Arrays.copyOf(from, n);
            tie = Arrays.copyOf(tie, n);
            edge = Arrays.copyOf(edge, n);
        }
        int it = items++;
        key[it] = k;
        g[it] = gValue;
        node[it] = nodeId;
        from[it] = fromId;
        tie[it] = tieOption;
        edge[it] = edgeEval;
        if (size == heap.length) {
            heap = Arrays.copyOf(heap, size * 2);
        }
        int i = size++;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (key[heap[p]] <= k) {
                break;
            }
            heap[i] = heap[p];
            i = p;
        }
        heap[i] = it;
    }

    /** Removes the item with the smallest key and returns its index. */
    int pop() {
        int top = heap[0];
        int last = heap[--size];
        int i = 0;
        double k = key[last];
        while (true) {
            int c = 2 * i + 1;
            if (c >= size) {
                break;
            }
            if (c + 1 < size && key[heap[c + 1]] < key[heap[c]]) {
                c++;
            }
            if (key[heap[c]] >= k) {
                break;
            }
            heap[i] = heap[c];
            i = c;
        }
        if (size > 0) {
            heap[i] = last;
        }
        return top;
    }
}
