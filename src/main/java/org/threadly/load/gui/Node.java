package org.threadly.load.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.threadly.util.Clock;
import org.threadly.util.StringUtils;

/**
 * <p>Class which represents a node on the graph.</p>
 * 
 * @author jent - Mike Jensen
 */
public class Node {
  private static final String JOIN_NAME = StringUtils.randomString(5);  // can be short due to identity comparison

  protected final ArrayList<Node> parentNodes;
  protected final ArrayList<Node> childNodes;
  protected final String name;
  /**
   * Constructs a new graph node with a specified identifier.  This is a node which is a branch or 
   * fork point.
   */
  public Node() {
    this(JOIN_NAME);
  }
  
  /**
   * Constructs a new graph node with a specified identifier.
   * 
   * @param name Identifier for this node
   */
  public Node(String name) {
    this.name = name;
    childNodes = new ArrayList<Node>(2);
    parentNodes = new ArrayList<Node>(2);
  }
  
  /**
   * Gets the name this node was constructed with.
   * 
   * @return Name of this node
   */
  public String getName() {
    if (isJoinNode()) {
      return "";
    } else {
      return name;
    }
  }
  
  /**
   * Indicates this node is a node where multiple nodes join into.  This indicates only a sync 
   * point where all nodes must reach before moving on to any child nodes.
   * 
   * @return {@code True} indicates a sync point in the graph
   */
  public boolean isJoinNode() {
    // we do an identify comparison here for efficiency as well as to avoid name conflicts
    return JOIN_NAME == name;
  }
  
  @Override
  public String toString() {
    return "node:" + name;
  }
  
  /**
   * Adds a node to be represented as a child node to this current instance.
   * 
   * @param node Node to be added as a child
   */
  public void addChildNode(Node node) {
    if (! childNodes.contains(node)) {
      if (! node.parentNodes.contains(this)) {
        node.parentNodes.add(this);
      }
      childNodes.add(node);
    }
  }
  
  /**
   * A collection of child nodes attached to this node.
   * 
   * @return A collection of child node references
   */
  public List<Node> getChildNodes() {
    return Collections.unmodifiableList(childNodes);
  }
  
  /**
   * A collection of parent nodes which attach to this node.
   * 
   * @return A collection of parent node references
   */
  public List<Node> getParentNodes() {
    return Collections.unmodifiableList(parentNodes);
  }

  /**
   * Removes this node from the graph.
   */
  protected void deleteFromGraph() {
    for (Node n: parentNodes) {
      n.childNodes.remove(this);
    }
    for (Node n: childNodes) {
      n.parentNodes.remove(this);
    }
  }

  /**
   * Traverses and cleans the graph.  This cleans up duplicate information like multiple join points.
   */
  public void cleanGraph() {
    long startTime = Clock.lastKnownForwardProgressingMillis();
    while (true) {
      try {
        doCleanGraph();
        break;
      } catch (StackOverflowError e) {
        System.err.println("Attempting to clean graph..." + 
                             (Clock.lastKnownForwardProgressingMillis() - startTime) / 1000 + "seconds");
      }
    }
  }
  
  private void doCleanGraph() {
    if (! isJoinNode()) {
      // removes child node that is a join node since all items can join off this node
      /*if (childNodes.size() == 1) {
        Node childNode = childNodes.get(0);
        if (childNode.isJoinNode() && childNode.parentNodes.size() == 1) {
          childNodes.clear();
          childNodes.addAll(childNode.childNodes);
        }
      }*/
      // removes parent node if our node can function as join node
      if (parentNodes.size() == 1) {
        Node parentNode = parentNodes.get(0);
        if (parentNode.isJoinNode() && parentNode.childNodes.size() < 2) {
          parentNode.deleteFromGraph();
          for(Node n : parentNode.parentNodes) {
            n.childNodes.remove(parentNode);
            n.addChildNode(this);
          }
        }
      }
    } else {
      // loops till consistent state, break at bottom
      while (true) {
        // removes tail node on graph that has no children and is a synthetic join node
        if (childNodes.isEmpty()) {
          deleteFromGraph();
          return;
        } else if (parentNodes.size() == 1) {
          // remove this node and instead connect parent node to our children
          Node parentNode = parentNodes.get(0);
          for (Node childNode : childNodes) {
            parentNode.addChildNode(childNode);
          }
          parentNode.childNodes.remove(this);
          parentNode.doCleanGraph();
          return;
        }
        // if all child nodes are join nodes, make this node function as the join node
        boolean modifiedNodes = false;
        List<Node> originalNodes;
        do {
          boolean allChildrenAreJoinNodes = ! childNodes.isEmpty();
          for (Node n : childNodes) {
            if (! n.isJoinNode()) {
              allChildrenAreJoinNodes = false;
              break;
            }
          }
          if (allChildrenAreJoinNodes) {
            originalNodes = new ArrayList<Node>(childNodes);
            for (int i = 0; i < originalNodes.size(); i++) {
              Node childNode = originalNodes.get(i);
              if (childNode.parentNodes.size() == 1) {
                for (Node childsChild : childNode.childNodes) {
                  addChildNode(childsChild);
                }
                modifiedNodes = childNodes.remove(childNode) || 
                                  modifiedNodes || ! childNode.childNodes.isEmpty();
              }
            }
          } else {
            originalNodes = null;
          }
        } while (originalNodes != null && ! originalNodes.equals(childNodes));  // loop through all join only nodes
        if (modifiedNodes) {
          continue; // restart check if children change
        } else {
          break;
        }
      }
    }
  
    // traverse to all child nodes to inspect themselves
    tillConsistent: while (true) {
      List<Node> originalNodes = new ArrayList<Node>(childNodes);
      for (int i = 0; i < originalNodes.size(); i++) {
        originalNodes.get(i).doCleanGraph();
        if (! originalNodes.equals(childNodes)) {
          continue tillConsistent;
        }
      }
      break;
    }
    
    // cleanup memory if possible
    childNodes.trimToSize();
    parentNodes.trimToSize();
  }
}
