/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master.balancer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionReplicaUtil;
import org.apache.hadoop.hbase.master.RackManager;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.apache.hadoop.hbase.testclassification.MasterTests;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.hbase.thirdparty.com.google.common.collect.ImmutableList;

@Category({ MasterTests.class, LargeTests.class })
public class TestStochasticLoadBalancerRegionReplica extends StochasticBalancerTestBase {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestStochasticLoadBalancerRegionReplica.class);

  @Test
  public void testReplicaCost() {
    Configuration conf = HBaseConfiguration.create();
    CostFunction costFunction = new RegionReplicaHostCostFunction(conf);
    for (int[] mockCluster : clusterStateMocks) {
      BalancerClusterState cluster = mockCluster(mockCluster);
      costFunction.prepare(cluster);
      double cost = costFunction.cost();
      assertTrue(cost >= 0);
      assertTrue(cost <= 1.01);
    }
  }

  @Test
  public void testReplicaCostForReplicas() {
    Configuration conf = HBaseConfiguration.create();
    CostFunction costFunction = new RegionReplicaHostCostFunction(conf);

    int[] servers = new int[] { 3, 3, 3, 3, 3 };
    TreeMap<ServerName, List<RegionInfo>> clusterState = mockClusterServers(servers);

    BalancerClusterState cluster;

    cluster = new BalancerClusterState(clusterState, null, null, null);
    costFunction.prepare(cluster);
    double costWithoutReplicas = costFunction.cost();
    assertEquals(0, costWithoutReplicas, 0);

    // replicate the region from first server to the last server
    RegionInfo replica1 =
      RegionReplicaUtil.getRegionInfoForReplica(clusterState.firstEntry().getValue().get(0), 1);
    clusterState.lastEntry().getValue().add(replica1);

    cluster = new BalancerClusterState(clusterState, null, null, null);
    costFunction.prepare(cluster);
    double costWith1ReplicaDifferentServer = costFunction.cost();

    assertEquals(0, costWith1ReplicaDifferentServer, 0);

    // add a third replica to the last server
    RegionInfo replica2 = RegionReplicaUtil.getRegionInfoForReplica(replica1, 2);
    clusterState.lastEntry().getValue().add(replica2);

    cluster = new BalancerClusterState(clusterState, null, null, null);
    costFunction.prepare(cluster);
    double costWith1ReplicaSameServer = costFunction.cost();

    assertTrue(costWith1ReplicaDifferentServer < costWith1ReplicaSameServer);

    // test with replication = 4 for following:

    RegionInfo replica3;
    Iterator<Map.Entry<ServerName, List<RegionInfo>>> it;
    Map.Entry<ServerName, List<RegionInfo>> entry;

    clusterState = mockClusterServers(servers);
    it = clusterState.entrySet().iterator();
    entry = it.next(); // first server
    RegionInfo hri = entry.getValue().get(0);
    replica1 = RegionReplicaUtil.getRegionInfoForReplica(hri, 1);
    replica2 = RegionReplicaUtil.getRegionInfoForReplica(hri, 2);
    replica3 = RegionReplicaUtil.getRegionInfoForReplica(hri, 3);
    entry.getValue().add(replica1);
    entry.getValue().add(replica2);
    it.next().getValue().add(replica3); // 2nd server

    cluster = new BalancerClusterState(clusterState, null, null, null);
    costFunction.prepare(cluster);
    double costWith3ReplicasSameServer = costFunction.cost();

    clusterState = mockClusterServers(servers);
    hri = clusterState.firstEntry().getValue().get(0);
    replica1 = RegionReplicaUtil.getRegionInfoForReplica(hri, 1);
    replica2 = RegionReplicaUtil.getRegionInfoForReplica(hri, 2);
    replica3 = RegionReplicaUtil.getRegionInfoForReplica(hri, 3);

    clusterState.firstEntry().getValue().add(replica1);
    clusterState.lastEntry().getValue().add(replica2);
    clusterState.lastEntry().getValue().add(replica3);

    cluster = new BalancerClusterState(clusterState, null, null, null);
    costFunction.prepare(cluster);
    double costWith2ReplicasOnTwoServers = costFunction.cost();

    assertTrue(costWith2ReplicasOnTwoServers < costWith3ReplicasSameServer);
  }

  @Test
  public void testNeedsBalanceForColocatedReplicasOnHost() {
    // check for the case where there are two hosts and with one rack, and where
    // both the replicas are hosted on the same server
    List<RegionInfo> regions = randomRegions(1);
    ServerName s1 = ServerName.valueOf("host1", 1000, 11111);
    ServerName s2 = ServerName.valueOf("host11", 1000, 11111);
    Map<ServerName, List<RegionInfo>> map = new HashMap<>();
    map.put(s1, regions);
    regions.add(RegionReplicaUtil.getRegionInfoForReplica(regions.get(0), 1));
    // until the step above s1 holds two replicas of a region
    regions = randomRegions(1);
    map.put(s2, regions);
    BalancerClusterState cluster =
      new BalancerClusterState(map, null, null, new ForTestRackManagerOne());
    loadBalancer.initCosts(cluster);
    assertTrue(loadBalancer.needsBalance(HConstants.ENSEMBLE_TABLE_NAME, cluster));
  }

  @Test
  public void testNeedsBalanceForColocatedReplicasOnRack() {
    // Three hosts, two racks, and two replicas for a region. This should be balanced
    List<RegionInfo> regions = randomRegions(1);
    ServerName s1 = ServerName.valueOf("host1", 1000, 11111);
    ServerName s2 = ServerName.valueOf("host11", 1000, 11111);
    Map<ServerName, List<RegionInfo>> map = new HashMap<>();
    List<RegionInfo> regionsOnS2 = new ArrayList<>(1);
    regionsOnS2.add(RegionReplicaUtil.getRegionInfoForReplica(regions.get(0), 1));
    map.put(s1, regions);
    map.put(s2, regionsOnS2);
    // add another server so that the cluster has some host on another rack
    map.put(ServerName.valueOf("host2", 1000, 11111), randomRegions(1));
    BalancerClusterState cluster =
      new BalancerClusterState(map, null, null, new ForTestRackManagerOne());
    loadBalancer.initCosts(cluster);
    assertTrue(loadBalancer.needsBalance(HConstants.ENSEMBLE_TABLE_NAME, cluster));
  }

  @Test
  public void testNoNeededBalanceForColocatedReplicasTooFewRacks() {
    // Three hosts, two racks, and three replicas for a region. This cannot be balanced
    List<RegionInfo> regions = randomRegions(1);
    ServerName s1 = ServerName.valueOf("host1", 1000, 11111);
    ServerName s2 = ServerName.valueOf("host11", 1000, 11111);
    ServerName s3 = ServerName.valueOf("host2", 1000, 11111);
    Map<ServerName, List<RegionInfo>> map = new HashMap<>();
    List<RegionInfo> regionsOnS2 = new ArrayList<>(1);
    regionsOnS2.add(RegionReplicaUtil.getRegionInfoForReplica(regions.get(0), 1));
    map.put(s1, regions);
    map.put(s2, regionsOnS2);
    // there are 3 replicas for region 0, but only add a second rack
    map.put(s3, ImmutableList.of(RegionReplicaUtil.getRegionInfoForReplica(regions.get(0), 2)));
    BalancerClusterState cluster =
      new BalancerClusterState(map, null, null, new ForTestRackManagerOne());
    loadBalancer.initCosts(cluster);
    // Should be false because there aren't enough racks
    assertFalse(loadBalancer.needsBalance(HConstants.ENSEMBLE_TABLE_NAME, cluster));
  }

  @Test
  public void testRegionReplicasOnSmallCluster() {
    int numNodes = 10;
    int numRegions = 1000;
    int replication = 3; // 3 replicas per region
    int numRegionsPerServer = 80; // all regions are mostly balanced
    int numTables = 10;
    testWithClusterWithIteration(numNodes, numRegions, numRegionsPerServer, replication, numTables,
      true, true);
  }

  private static class ForTestRackManagerOne extends RackManager {
    @Override
    public String getRack(ServerName server) {
      return server.getHostname().endsWith("1") ? "rack1" : "rack2";
    }
  }
}
