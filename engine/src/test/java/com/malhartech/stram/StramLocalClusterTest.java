/**
 * Copyright (c) 2012-2012 Malhar, Inc.
 * All rights reserved.
 */
package com.malhartech.stram;

import static com.malhartech.stram.conf.TopologyBuilder.STRAM_WINDOW_SIZE_MILLIS;
import static com.malhartech.stram.conf.TopologyBuilder.STREAM_CLASSNAME;
import static com.malhartech.stram.conf.TopologyBuilder.STREAM_INLINE;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.junit.Assert;
import org.junit.Test;

import com.malhartech.dag.InputAdapter;
import com.malhartech.dag.InternalNode;
import com.malhartech.dag.StreamConfiguration;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.ContainerHeartbeatResponse;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StramToNodeRequest;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StramToNodeRequest.RequestType;
import com.malhartech.stram.conf.TopologyBuilder;
import com.malhartech.stram.conf.TopologyBuilder.NodeConf;
import com.malhartech.stram.conf.TopologyBuilder.StreamConf;
import com.malhartech.stream.AbstractInputAdapter;
import com.malhartech.stream.HDFSOutputStream;

public class StramLocalClusterTest {

  @Test
  public void testLocalClusterInitShutdown() {
    // create test topology
    Properties props = new Properties();

    // input adapter to ensure shutdown works on end of stream
    props.put("stram.stream.input1.classname", NumberGeneratorInputAdapter.class.getName());
    props.put("stram.stream.input1.outputNode", "node1");
    props.put("stram.stream.input1.maxTuples", "1");

    // fake output adapter - to be ignored when determine shutdown
    props.put("stram.stream.output.classname", HDFSOutputStream.class.getName());
    props.put("stram.stream.output.inputNode", "node2");
    props.put("stram.stream.output.filepath", "miniclustertest-testSetupShutdown.out");

    props.put("stram.stream.n1n2.inputNode", "node1");
    props.put("stram.stream.n1n2.outputNode", "node2");
    props.put("stram.stream.n1n2.template", "defaultstream");

    props.put("stram.node.node1.classname", TopologyBuilderTest.EchoNode.class.getName());
    props.put("stram.node.node1.myStringProperty", "myStringPropertyValue");

    props.put("stram.node.node2.classname", TopologyBuilderTest.EchoNode.class.getName());

    props.setProperty(TopologyBuilder.NUM_CONTAINERS, "2");

    TopologyBuilder tplg = new TopologyBuilder(new Configuration());
    tplg.addFromProperties(props);
    
    StramLocalCluster localCluster = new StramLocalCluster(tplg);
    localCluster.run();
  }
  
  @Test
  public void testChildRecovery() throws Exception {

    TopologyBuilder tb = new TopologyBuilder();
    tb.getConf().setInt(STRAM_WINDOW_SIZE_MILLIS, 0); // disable window generator
    
    StreamConf input1 = tb.getOrAddStream("input1");
    input1.addProperty(STREAM_CLASSNAME,
        LocalTestInputAdapter.class.getName());
    input1.addProperty(STREAM_INLINE, "true");
    //input1.addProperty("maxTuples", "1");
    
    NodeConf node1 = tb.getOrAddNode("node1");
    node1.addInput(input1);

    for (NodeConf nodeConf : tb.getAllNodes().values()) {
      nodeConf.setClassName(TopologyBuilderTest.EchoNode.class.getName());
    }
    
    StramLocalCluster localCluster = new StramLocalCluster(tb);
    localCluster.runAsync();

    StramChild c0 = waitForContainer(localCluster, node1);
    
    List<InputAdapter> inputAdapters = c0.getInputAdapters();
    Assert.assertEquals("number input adapters", 1, inputAdapters.size());

    Map<String, InternalNode> nodeMap = c0.getNodeMap();
    Assert.assertEquals("number nodes", 2, nodeMap.size());

    // safer to lookup via topology deployer
    InternalNode n1 = nodeMap.get("1");
    
    LocalTestInputAdapter input = (LocalTestInputAdapter)inputAdapters.get(0);
    Assert.assertEquals("initial window id", 0, input.getContext().getStartingWindowId());
    input.resetWindow(0, 1);
    input.beginWindow(1);

    backupNode(c0, n1);
    input.endWindow(1);
    
    input.beginWindow(2);
    input.endWindow(2);

    // wait for node to move to next window
    while (n1.getContext().getCurrentWindowId() < 2) {
      Thread.sleep(100);
    }
    // propagate checkpoint to master
    c0.triggerHeartbeat();
    // wait for heartbeat cycle to complete
    Thread.sleep(100); // TODO: make event driven
    // simulate node failure 
    localCluster.failContainer(c0);

    StramChild c1 = waitForContainer(localCluster, node1);
    Assert.assertNotSame("old container", c0, c1);
    Assert.assertNotSame("old container", c0.getContainerId(), c1.getContainerId());

    Thread.sleep(500); // TODO: container initialized event
    inputAdapters = c1.getInputAdapters();
    input = (LocalTestInputAdapter)inputAdapters.get(0);
    Assert.assertEquals("number input adapters", 1, inputAdapters.size());
    Assert.assertEquals("initial window id", 1, input.getContext().getStartingWindowId());
    
    int i =0;
    
  }

  private StramChild waitForContainer(StramLocalCluster localCluster, NodeConf nodeConf) {
    StramChild container;
    // wait for containers to come up
    while ((container = localCluster.findContainerByLogicalNode(nodeConf)) == null) {
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
      }
    }
    Assert.assertNotNull(container);
    return container;
  }
  
  private void backupNode(StramChild c, InternalNode node) {
    StramToNodeRequest backupRequest = new StramToNodeRequest();
    backupRequest.setNodeId(node.getContext().getId());
    backupRequest.setRequestType(RequestType.CHECKPOINT);
    ContainerHeartbeatResponse rsp = new ContainerHeartbeatResponse();
    rsp.setNodeRequests(Collections.singletonList(backupRequest));
    // TODO: ensure node is running and context set (startup timing)
    c.processHeartbeatResponse(rsp);
  }
  
  public static class LocalTestInputAdapter extends AbstractInputAdapter {

    @Override
    public void setup(StreamConfiguration config) {
    }

    @Override
    public void activate() {
    }

    @Override
    public void teardown() {
    }
    
  }
  
  
}
