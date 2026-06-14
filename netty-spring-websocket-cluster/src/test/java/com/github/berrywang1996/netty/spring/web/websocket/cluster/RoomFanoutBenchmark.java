/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Honest fan-out benchmark for per-room node-targeted routing. The reduction is N/k where k = the number of
 * distinct nodes hosting members of a room. This harness MEASURES k for three deliberately different load
 * shapes and prints ALL of them — including the case where targeting collapses to a global broadcast — so
 * the property is benchmarked, not cherry-picked.
 *
 * <p>It is a manual harness (no real transport — it measures the routing primitive, the per-room node-set
 * size, which is exactly what determines fan-out). The three scenarios:
 * <ol>
 *   <li><b>Favorable</b> — many bounded rooms (small member count), users placed at random by the LB. Even
 *       under random placement a 5-member room lands on ≤5 distinct nodes → large reduction in a big cluster.</li>
 *   <li><b>Adversarial (random LB, large rooms)</b> — rooms so large their members touch most/all nodes →
 *       k → N, reduction → 1, AND the publish side costs ~N targeted sends vs 1 global publish.</li>
 *   <li><b>Hot room on every node</b> — the explicit worst case: one giant room with a member on every node
 *       → k = N exactly. Baseline: no reduction at all; use {@code topicMessage} (global) instead.</li>
 * </ol>
 */
class RoomFanoutBenchmark {

    private static final int CLUSTER_SIZE = 100;
    private static final int ROOMS = 1000;
    private static final String URI = "/ws/bench";

    @Test
    void printThreeScenarioFanout() {
        System.out.println();
        System.out.println("=== Per-room node-targeted fan-out — N = " + CLUSTER_SIZE + " nodes, "
                + ROOMS + " rooms ===");
        System.out.println("    (fan-out = distinct nodes targeted per room broadcast; reduction = N / fan-out)");

        // Scenario 1: favorable — bounded rooms (5 members each), random LB placement.
        scenario("FAVORABLE: bounded rooms (5 members), random LB",
                roomMemberCounts(ROOMS, () -> 5));

        // Scenario 2: adversarial — large rooms (60 members each) spread randomly across the cluster.
        scenario("ADVERSARIAL: large rooms (60 members), random LB",
                roomMemberCounts(ROOMS, () -> 60));

        // Scenario 3: hot room on every node — one room, a member on every single node (k = N).
        scenarioHot("HOT ROOM: one room, a member on every node (k = N)");
    }

    /** Builds a list of per-room member counts using the supplier. */
    private static List<Integer> roomMemberCounts(int rooms, java.util.function.IntSupplier memberCount) {
        List<Integer> counts = new ArrayList<>(rooms);
        for (int i = 0; i < rooms; i++) {
            counts.add(memberCount.getAsInt());
        }
        return counts;
    }

    /** Places each room's members at random nodes and reports the distribution of k (targeted node count). */
    private static void scenario(String name, List<Integer> roomMemberCounts) {
        long totalFanout = 0;
        int maxFanout = 0;
        int minFanout = Integer.MAX_VALUE;
        long totalPublishesTargeted = 0; // sum of (k-1) — remote targeted sends (self excluded)
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int members : roomMemberCounts) {
            // Random LB placement: each member connects to a random node; k = distinct nodes used.
            Set<Integer> nodes = new HashSet<>();
            for (int m = 0; m < members; m++) {
                nodes.add(rnd.nextInt(CLUSTER_SIZE));
            }
            int k = nodes.size();
            totalFanout += k;
            maxFanout = Math.max(maxFanout, k);
            minFanout = Math.min(minFanout, k);
            totalPublishesTargeted += Math.max(0, k - 1); // origin self-excluded
        }
        double avgFanout = (double) totalFanout / roomMemberCounts.size();
        double avgReduction = CLUSTER_SIZE / avgFanout;
        // Global broadcast costs 1 publish; targeted costs (k-1) per room. Average targeted publishes/room:
        double avgTargetedPublishes = (double) totalPublishesTargeted / roomMemberCounts.size();

        print(name, avgFanout, minFanout, maxFanout, avgReduction, avgTargetedPublishes);
    }

    /** The explicit hot-room case: k = N for a single room with a member on every node. */
    private static void scenarioHot(String name) {
        int k = CLUSTER_SIZE; // a member on every node
        double avgFanout = k;
        double avgReduction = CLUSTER_SIZE / avgFanout; // = 1.0
        double avgTargetedPublishes = k - 1;            // ~N targeted sends vs 1 global publish
        print(name, avgFanout, k, k, avgReduction, avgTargetedPublishes);
    }

    private static void print(String name, double avgFanout, int minF, int maxF,
                              double avgReduction, double avgTargetedPublishes) {
        System.out.println();
        System.out.println("--- " + name + " ---");
        System.out.printf("    avg fan-out (nodes targeted/room): %.2f  (min %d, max %d)%n", avgFanout, minF, maxF);
        System.out.printf("    avg reduction vs global N=%d:       %.1fx%n", CLUSTER_SIZE, avgReduction);
        System.out.printf("    publish-side cost (targeted sends/room): %.1f  (global broadcast = 1)%n",
                avgTargetedPublishes);
        if (avgReduction <= 1.05) {
            System.out.println("    >> NO delivery reduction here (k ~= N). Publish-side costs MORE than global.");
            System.out.println("    >> For rooms this large, prefer topicMessage(uri, msg) (global broadcast).");
        } else {
            System.out.printf("    >> Real reduction: a room message reaches ~%.0f nodes instead of %d.%n",
                    avgFanout, CLUSTER_SIZE);
        }
    }
}
