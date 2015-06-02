package org.threadly.load.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.DragDetectEvent;
import org.eclipse.swt.events.DragDetectListener;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.MouseWheelListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.threadly.concurrent.PrioritySchedulerInterface;
import org.threadly.util.ArgumentVerifier;
import org.threadly.util.Clock;

/**
 * <p>Class which handles drawing a window to show a given graph.</p>
 *
 * @author jent - Mike Jensen
 */
public class AmbushGraph {
  private static final int LARGE_X_SIZE = 1440;
  private static final int LARGE_Y_SIZE = 900;
  private static final int SMALL_X_SIZE = 1280;
  private static final int SMALL_Y_SIZE = 1024;
  private static final int PREVIEW_X_SIZE = 640;
  private static final int DRAG_TOLLERANCE = 25;
  private static final int HIGHLIGHT_DISAPEAR_DELAY = 2000;
  private static final int BACKGROUND_GRAY = 210;
  private static final int GRID_SOFTNESS = 50;  // randomness for point placement
  private static final int DISTANCE_FROM_EDGE = 50;  // dots wont be placed within this distance from the edge
  private static final int SQUEEZE_FACTOR = 2;  // smaller numbers result in tighter plot groups
  private static final int MAX_NODES_DRAW_ALL_NAMES = 20;
  private static final Random RANDOM = new Random(Clock.lastKnownTimeMillis());

  private final PrioritySchedulerInterface scheduler;
  private final Color backgroundColor;
  private final Shell mainShell;
  private final Shell previewShell;
  private final Runnable redrawRunnable;
  private volatile GraphDataSet currentDataSet;

  /**
   * Constructs a new window which will display the graph of nodes.  Nodes will be provided via
   * {@link #updateGraphModel(Node)}.
   *
   * @param scheduler Scheduler to schedule and execute tasks on to
   * @param display A non-disposed display to open the shell on
   */
  public AmbushGraph(PrioritySchedulerInterface scheduler, Display display) {
    this(scheduler, display, -1, -1);
  }

  /**
   * Constructs a new window which will display the graph of nodes.  Nodes will be provided via
   * {@link #updateGraphModel(Node)}.  This constructor allows you to specify the original window
   * size.
   *
   * @param scheduler Scheduler to schedule and execute tasks on to
   * @param display A non-disposed display to open the shell on
   * @param xSize Width in pixels for the window
   * @param ySize Height in pixels for the window
   */
  public AmbushGraph(PrioritySchedulerInterface scheduler, Display display, int xSize, int ySize) {
    ArgumentVerifier.assertNotNull(scheduler, "scheduler");

    if (xSize < 1 || ySize < 1) {
      Rectangle displayBounds = display.getBounds();
      if (displayBounds.width > LARGE_X_SIZE && displayBounds.height > LARGE_Y_SIZE) {
        xSize = LARGE_X_SIZE;
        ySize = LARGE_Y_SIZE;
      } else {
        xSize = SMALL_X_SIZE;
        ySize = SMALL_Y_SIZE;
      }
    }
    redrawRunnable = new Runnable() {
      private final AtomicBoolean displayTaskExeced = new AtomicBoolean();

      @Override
      public void run() {
        if (! mainShell.isDisposed() && ! mainShell.getDisplay().isDisposed()) {
          if (displayTaskExeced.compareAndSet(false, true)) {
            mainShell.getDisplay().asyncExec(new Runnable() {
              @Override
              public void run() {
                displayTaskExeced.set(false);
                redraw();
              }
            });
          }
        } else {
          AmbushGraph.this.scheduler.remove(this);
        }
      }
    };

    this.scheduler = scheduler;
    backgroundColor = new Color(display, BACKGROUND_GRAY, BACKGROUND_GRAY, BACKGROUND_GRAY);

    mainShell = new Shell(display);
    mainShell.setText("Ambush execution graph");
    mainShell.setSize(xSize, ySize);
    mainShell.setBackground(backgroundColor);

    mainShell.addListener(SWT.Paint, new Listener() {
      @Override
      public void handleEvent(Event arg0) {
        updateDisplay(arg0.gc, false);
      }
    });
    new MainWindowListener().registerListener();

    previewShell = new Shell(display);
    previewShell.setText("Ambush preview");
    previewShell.setSize(PREVIEW_X_SIZE, (int)(PREVIEW_X_SIZE * ((double)ySize) / xSize));
    previewShell.setBackground(backgroundColor);

    previewShell.addListener(SWT.Paint, new Listener() {
      @Override
      public void handleEvent(Event arg0) {
        updateDisplay(arg0.gc, true);
      }
    });
    new PreviewWindowListener().registerListener();

    currentDataSet = new GraphDataSet(xSize, ySize);
  }

  /**
   * Opens the shell and handles doing the read and dispatch loop for the display.  This call will
   * block until the shell is closed.
   */
  public void runGuiLoop() {
    mainShell.open();
    previewShell.open();

    while (! mainShell.isDisposed()) {
      Display disp = mainShell.getDisplay();
      if (! disp.readAndDispatch()) {
        disp.sleep();
      }
    }
  }

  /**
   * Updates the graph representation.  This call will start crawling from the head node provided
   * to explore all child nodes.
   *
   * @param headNode Node to start building graph from
   */
  public void updateGraphModel(Node headNode) {
    Map<Node, GuiPoint> buildingMap = new HashMap<Node, GuiPoint>();
    Map<Integer, List<GuiPoint>> xRegionCountMap = new HashMap<Integer, List<GuiPoint>>();
    GraphDataSet newDataSet = new GraphDataSet(currentDataSet.naturalBounds.x, currentDataSet.naturalBounds.y);
    traverseNode(newDataSet, headNode, buildingMap, 1, 1, new AtomicInteger(), xRegionCountMap);

    // cleanup xRegionCountMap
    int maxYCount = 0;
    Iterator<List<GuiPoint>> it = xRegionCountMap.values().iterator();
    while (it.hasNext()) {
      List<GuiPoint> xRegion = it.next();
      Collections.sort(xRegion, new Comparator<GuiPoint>() {
        @Override
        public int compare(GuiPoint o1, GuiPoint o2) {
          return o1.yRegion - o2.yRegion;
        }
      });
      Iterator<GuiPoint> points = xRegion.iterator();
      int currentPoint = 0;
      while (points.hasNext()) {
        points.next().yRegion = ++currentPoint;
      }
      if (currentPoint > maxYCount) {
        maxYCount = currentPoint;
      }
    }

    newDataSet.setData(buildingMap, headNode);

    synchronized (this) {
      currentDataSet = newDataSet;

      if (zoomedIn(currentDataSet)) {
        updateMainOrigin(0, (int)(currentDataSet.naturalBounds.y * currentDataSet.zoomFactor) / 2 - (mainShell.getSize().y / 2));
      }
      redrawRunnable.run();
    }
  }

  private void traverseNode(GraphDataSet newDataSet,
                            Node currentNode, Map<Node, GuiPoint> buildingMap,
                            int xRegion, int yRegion, AtomicInteger maxYRegion,
                            Map<Integer, List<GuiPoint>> xRegionCountMap) {
    if (maxYRegion.get() < yRegion) {
      maxYRegion.set(yRegion);
    }
    GuiPoint currentPoint = buildingMap.get(currentNode);
    if (currentPoint == null) {
      currentPoint = new GuiPoint(makeRandomColor(), newDataSet.naturalBounds,
                                  xRegionCountMap, xRegion, yRegion);
      buildingMap.put(currentNode, currentPoint);
      add(currentPoint, xRegionCountMap);
      int childNodeRegion = maxYRegion.get();
      Iterator<Node> it = currentNode.getChildNodes().iterator();
      while (it.hasNext()) {
        traverseNode(newDataSet, it.next(), buildingMap,
                     xRegion + 1, ++childNodeRegion, maxYRegion, xRegionCountMap);
      }
    } else {
      if (xRegion > currentPoint.xRegion) {
        Set<Node> inspectedNodes = new HashSet<Node>();
        inspectedNodes.add(currentNode);
        shiftLeft(currentNode, currentPoint, buildingMap,
                  xRegion - currentPoint.xRegion, xRegionCountMap, inspectedNodes);
      }
    }
  }

  private static void add(GuiPoint point, Map<Integer, List<GuiPoint>> map) {
    List<GuiPoint> currList = map.get(point.xRegion);
    if (currList == null) {
      currList = new LinkedList<GuiPoint>();
      map.put(point.xRegion, currList);
    }
    if (! currList.contains(point)) {
      currList.add(point);
    }
  }

  private static void remove(GuiPoint point, Map<Integer, List<GuiPoint>> map) {
    List<GuiPoint> currList = map.get(point.xRegion);
    if (currList != null) {
      currList.remove(point);
    }
  }

  private void shiftLeft(Node currNode, GuiPoint point,
                         Map<Node, GuiPoint> buildingMap, int shiftAmount,
                         Map<Integer, List<GuiPoint>> xRegionCountMap, Set<Node> shiftedNodes) {
    remove(point, xRegionCountMap);
    point.xRegion += shiftAmount;
    add(point, xRegionCountMap);
    Iterator<Node> it = currNode.getChildNodes().iterator();
    while (it.hasNext()) {
      Node child = it.next();
      if (shiftedNodes.contains(child)) {
        continue;
      } else {
        shiftedNodes.add(child);
      }
      GuiPoint childPoint = buildingMap.get(child);
      if (childPoint != null) {
        shiftLeft(child, childPoint, buildingMap, shiftAmount, xRegionCountMap, shiftedNodes);
      }
    }
  }

  private void updateDisplay(GC gc, boolean preview) {
    GraphDataSet dataSet = this.currentDataSet;
    //gc.setBackground(new Color(shell.getDisplay(), 230, 230, 230));
    //gc.fillRectangle(0, 0, XSIZE, YSIZE);
    Iterator<Entry<Node, GuiPoint>> it = dataSet.guiNodeMap.entrySet().iterator();
    while (it.hasNext()) {
      Entry<Node, GuiPoint> entry = it.next();
      // draw a dot to indicate node point
      gc.setForeground(entry.getValue().color);
      int pointX = (int)(entry.getValue().getX() * dataSet.zoomFactor);
      int pointY = (int)(entry.getValue().getY() * dataSet.zoomFactor);
      int size;
      if (preview) {
        double xFactor = previewShell.getSize().x / (dataSet.naturalBounds.x * dataSet.zoomFactor);
        pointX = (int)(pointX * xFactor);
        double yFactor = previewShell.getSize().y / (dataSet.naturalBounds.y * dataSet.zoomFactor);
        pointY = (int)(pointY * yFactor);
        size = 2;
      } else {
        pointX -= dataSet.mainOrigin.x;
        pointY -= dataSet.mainOrigin.y;
        size = 5;
      }
      gc.setBackground(entry.getValue().color);
      gc.fillOval(pointX, pointY, size, size);
      gc.setBackground(backgroundColor);

      // draw lines to peer nodes (which may or may not be drawn yet)
      Iterator<Node> it2 = entry.getKey().getChildNodes().iterator();
      while (it2.hasNext()) {
        Node child = it2.next();
        GuiPoint childPoint = dataSet.guiNodeMap.get(child);
        if (childPoint == null) {
          System.err.println("***** " + entry.getKey().getName() +
                               " is connected to an unknown node: " + child.getName() + " *****");
          continue;
        }
        int childX = (int)(childPoint.getX() * dataSet.zoomFactor);
        int childY = (int)(childPoint.getY() * dataSet.zoomFactor);
        if (preview) {
          double xFactor = previewShell.getSize().x / (dataSet.naturalBounds.x * dataSet.zoomFactor);
          childX = (int)(childX * xFactor);
          double yFactor = previewShell.getSize().y / (dataSet.naturalBounds.y * dataSet.zoomFactor);
          childY = (int)(childY * yFactor);
        } else {
          childX -= dataSet.mainOrigin.x;
          childY -= dataSet.mainOrigin.y;
        }

        gc.drawLine(pointX, pointY, childX, childY);
      }

      // Draw the label last
      if (! preview && (dataSet.drawAllNames || dataSet.highlightedPoint == entry.getValue())) {
        gc.setForeground(new Color(mainShell.getDisplay(), 0, 0, 0));
        gc.setBackground(backgroundColor);
        gc.drawText(entry.getKey().getName(), pointX + 10, pointY - 5);
      }
    }

    gc.setForeground(new Color(mainShell.getDisplay(), 0, 0, 0));
    if (preview) {
      if (zoomedIn(dataSet)) {
        double xFactor = previewShell.getSize().x / (dataSet.naturalBounds.x * dataSet.zoomFactor);
        double yFactor = previewShell.getSize().y / (dataSet.naturalBounds.y * dataSet.zoomFactor);
        int translatedMainOriginX = (int)(dataSet.mainOrigin.x * xFactor);
        int translatedMainOriginY = (int)(dataSet.mainOrigin.y * yFactor);
        int translatedMainWidth = (int)(mainShell.getSize().x * xFactor);
        int translatedMainHeight = (int)(mainShell.getSize().y * yFactor);
        gc.drawRectangle(translatedMainOriginX, translatedMainOriginY,
                         translatedMainWidth, translatedMainHeight);
      }
    } else {
      if (dataSet.drawAllNames) {
        gc.drawText("Hide names", 10, 10);
      } else {
        gc.drawText("Show names", 10, 10);
      }
    }
  }

  private boolean zoomedIn(GraphDataSet dataSet) {
    return dataSet.zoomFactor > 1 || 
             dataSet.naturalBounds.x > mainShell.getSize().x + 10 || 
             dataSet.naturalBounds.y > mainShell.getSize().y + 10;
  }

  private GuiPoint getClosestPoint(int x, int y) {
    GraphDataSet dataSet = this.currentDataSet;
    Iterator<GuiPoint> it = dataSet.guiNodeMap.values().iterator();
    GuiPoint minEntry = null;
    double minDistance = Double.MAX_VALUE;
    while (it.hasNext()) {
      GuiPoint point = it.next();
      int pointX = (int)(point.getX() * dataSet.zoomFactor);
      int pointY = (int)(point.getY() * dataSet.zoomFactor);
      if (Math.abs(pointX - dataSet.mainOrigin.x - x) <= DRAG_TOLLERANCE &&
          Math.abs(pointY - dataSet.mainOrigin.y - y) <= DRAG_TOLLERANCE) {
        double distance = Math.sqrt(Math.pow(Math.abs(pointX - dataSet.mainOrigin.x - x), 2) +
                                      Math.pow(Math.abs(pointY - dataSet.mainOrigin.y - y), 2));
        if (distance < minDistance) {
          minDistance = distance;
          minEntry = point;
        }
      }
    }
    return minEntry;
  }

  private void updateMainOrigin(int x, int y) {
    // TODO - this wont let us move the visual window to some positions if main window is shrunk down
    GraphDataSet dataSet = this.currentDataSet;
    if (x <= 0) {
      x = 0;
    } else if (x + dataSet.naturalBounds.x > dataSet.naturalBounds.x * dataSet.zoomFactor) {
      x = (int)(dataSet.naturalBounds.x * dataSet.zoomFactor) - dataSet.naturalBounds.x;
    }
    if (y <= 0) {
      y = 0;
    } else if (y + dataSet.naturalBounds.y > dataSet.naturalBounds.y * dataSet.zoomFactor) {
      y = (int)(dataSet.naturalBounds.y * dataSet.zoomFactor) - dataSet.naturalBounds.y;
    }
    dataSet.mainOrigin = new Point(x, y);

    redraw();
  }

  private void redraw() {
    if (! mainShell.isDisposed() && ! mainShell.getDisplay().isDisposed()) {
      if (mainShell.isVisible()) {
        mainShell.redraw();
      }
      if (! previewShell.isDisposed() && previewShell.isVisible()) {
        previewShell.redraw();
      }
    }
  }

  private Color makeRandomColor() {
    final int maxValue = 150;
    int r = RANDOM.nextInt(maxValue);
    int g = RANDOM.nextInt(maxValue);
    int b = RANDOM.nextInt(maxValue);
    return new Color(mainShell.getDisplay(), r, g, b);
  }

  private static int getSoftGridPoint(int region, int totalRegions, int maxDimension) {
    if (region < 1) {
      throw new IllegalArgumentException("Region must be >= 1: " + region);
    } else if (region > totalRegions) {
      throw new IllegalArgumentException("Region can not be beyond total regions: " + region + " / " + totalRegions);
    }
    int spacePerRegion = maxDimension / totalRegions;
    int pos = spacePerRegion / 2;
    pos += (region - 1) * spacePerRegion;
    int softness = RANDOM.nextInt(GRID_SOFTNESS);
    if (pos < DISTANCE_FROM_EDGE || (pos < maxDimension - DISTANCE_FROM_EDGE && RANDOM.nextBoolean())) {
      pos += softness;
    } else {
      pos -= softness;
    }
    if (pos < DISTANCE_FROM_EDGE) {
      pos = DISTANCE_FROM_EDGE;
    } else if (pos > maxDimension - DISTANCE_FROM_EDGE) {
      pos = maxDimension - DISTANCE_FROM_EDGE;
    }
    return pos;
  }

  /**
   * <p>This class handles all listener actions for the main window.</p>
   *
   * @author jent - Mike Jensen
   */
  private class MainWindowListener implements DragDetectListener, MouseListener, 
                                              MouseMoveListener, MouseWheelListener, ControlListener {
    public void registerListener() {
      mainShell.addDragDetectListener(this);
      mainShell.addMouseListener(this);
      mainShell.addMouseMoveListener(this);
      mainShell.addMouseWheelListener(this);
      mainShell.addControlListener(this);
    }

    @Override
    public void dragDetected(DragDetectEvent dde) {
      if (dde.button != 1) {
        return;
      }

      GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
      dataSet.movingPoint = getClosestPoint(dde.x, dde.y);
      if (dataSet.movingPoint == null && zoomedIn(dataSet)) {
        dataSet.dragPoint = new Point(dde.x, dde.y);
      }
    }

    @Override
    public void mouseDoubleClick(MouseEvent me) {
      // ignored
    }

    @Override
    public void mouseDown(MouseEvent me) {
      if (me.button != 1) {
        return;
      }

      if (me.x < 150 && me.y < 50) {
        GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
        dataSet.drawAllNames = ! dataSet.drawAllNames;
        if (! dataSet.drawAllNames) {
          dataSet.highlightedPoint = null;
        }
        mainShell.redraw();
      }
    }

    @Override
    public void mouseUp(MouseEvent me) {
      GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
      dataSet.movingPoint = null;
      dataSet.dragPoint = null;
    }

    @Override
    public void mouseMove(MouseEvent me) {
      GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
      if (dataSet.dragPoint != null) {
        if (dataSet.dragPoint.x != me.x || dataSet.dragPoint.y != me.y) {
          updateMainOrigin(dataSet.mainOrigin.x + dataSet.dragPoint.x - me.x,
                           dataSet.mainOrigin.y + dataSet.dragPoint.y - me.y);
          dataSet.dragPoint = new Point(me.x, me.y);
        }
      } else if (dataSet.movingPoint != null) {
        // first translate point on window to absolute
        int translatedX = (int)((me.x + dataSet.mainOrigin.x) / dataSet.zoomFactor);
        int translatedY = (int)((me.y + dataSet.mainOrigin.y) / dataSet.zoomFactor);
        // TODO - comment on logic bellow
        dataSet.movingPoint.setPosition(Math.max(Math.min(translatedX, (zoomedIn(dataSet) ? mainShell.getSize().x : (int)(mainShell.getSize().x * (1 / dataSet.zoomFactor))) - 25), 10),
                                        Math.max(Math.min(translatedY, (zoomedIn(dataSet) ? mainShell.getSize().y : (int)(mainShell.getSize().y * (1 / dataSet.zoomFactor))) - 45), 10));

        redraw();
      } else if (! dataSet.drawAllNames) {
        GuiPoint previousHighlighted = dataSet.highlightedPoint;
        dataSet.highlightedPoint = getClosestPoint(me.x, me.y);
        if (previousHighlighted != dataSet.highlightedPoint) {
          if (dataSet.highlightedPoint != null) {
            mainShell.redraw();
          } else {
            scheduler.schedule(redrawRunnable, HIGHLIGHT_DISAPEAR_DELAY);
          }
        }
      }
    }

    @Override
    public void mouseScrolled(MouseEvent me) {
      GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
      double newZoomFactor;
      if (me.count > 0) {
        if ( dataSet.zoomFactor > 5) {
          // already fully zoomed in
          return;
        }
        // scroll forward / zoom in
        newZoomFactor = dataSet.zoomFactor + .1;
        // TODO - zoom in mouse position?
      } else {
        if (dataSet.zoomFactor < .8) {
          // already fully zoomed out
          return;
        }
        // scroll back / zoom out
        newZoomFactor = dataSet.zoomFactor - .1;
      }
      int xZoomChange = (int)((mainShell.getSize().x * newZoomFactor) - (mainShell.getSize().x * dataSet.zoomFactor));
      int yZoomChange = (int)((mainShell.getSize().y * newZoomFactor) - (mainShell.getSize().y * dataSet.zoomFactor));
      
      dataSet.zoomFactor = newZoomFactor;
      
      int newX = dataSet.mainOrigin.x;
      int newY = dataSet.mainOrigin.y;
      newX += xZoomChange / 2;
      newY += yZoomChange / 2;
      updateMainOrigin(newX, newY);
    }

    @Override
    public void controlMoved(ControlEvent arg0) {
      // ignored
    }

    @Override
    public void controlResized(ControlEvent arg0) {
      redraw();
    }
  }

  /**
   * <p>This class handles all listener actions for the preview window.</p>
   *
   * @author jent - Mike Jensen
   */
  private class PreviewWindowListener implements DragDetectListener, MouseListener, 
                                                 MouseMoveListener, MouseWheelListener {
    public void registerListener() {
      previewShell.addDragDetectListener(this);
      previewShell.addMouseListener(this);
      previewShell.addMouseMoveListener(this);
      previewShell.addMouseWheelListener(this);
    }

    @Override
    public void dragDetected(DragDetectEvent arg0) {
      GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
      if (arg0.button != 1 || ! zoomedIn(dataSet)) {
        return;
      }

      double xFactor = previewShell.getSize().x / (dataSet.naturalBounds.x * dataSet.zoomFactor);
      double yFactor = previewShell.getSize().y / (dataSet.naturalBounds.y * dataSet.zoomFactor);
      int translatedMainOriginX = (int)(dataSet.mainOrigin.x * xFactor);
      int translatedMainOriginY = (int)(dataSet.mainOrigin.y * yFactor);
      int translatedMainWidth = (int)(mainShell.getSize().x * xFactor);
      int translatedMainHeight = (int)(mainShell.getSize().y * yFactor);
      if (arg0.x > translatedMainOriginX && arg0.x < translatedMainOriginX + translatedMainWidth &&
          arg0.y > translatedMainOriginY && arg0.y < translatedMainOriginY + translatedMainHeight) {
        dataSet.dragPoint = new Point(arg0.x, arg0.y);
      }
    }

    @Override
    public void mouseMove(MouseEvent me) {
      GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
      if (dataSet.dragPoint != null) {
        if (dataSet.dragPoint.x != me.x || dataSet.dragPoint.y != me.y) {
          double xFactor = (dataSet.naturalBounds.x * dataSet.zoomFactor) / previewShell.getSize().x;
          double yFactor = (dataSet.naturalBounds.y * dataSet.zoomFactor) / previewShell.getSize().y;
          updateMainOrigin((int)(dataSet.mainOrigin.x + ((me.x - dataSet.dragPoint.x) * xFactor)),
                           (int)(dataSet.mainOrigin.y + ((me.y - dataSet.dragPoint.y) * yFactor)));
          dataSet.dragPoint = new Point(me.x, me.y);
        }
      }
    }

    @Override
    public void mouseDoubleClick(MouseEvent me) {
      GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
      if (me.button != 1 || ! zoomedIn(dataSet)) {
        return;
      }

      double xFactor = (mainShell.getSize().x * dataSet.zoomFactor) / previewShell.getSize().x;
      double yFactor = (mainShell.getSize().y * dataSet.zoomFactor) / previewShell.getSize().y;
      updateMainOrigin((int)((me.x * xFactor) - (mainShell.getSize().x / 2)),
                       (int)((me.y * yFactor) - (mainShell.getSize().y / 2)));
    }

    @Override
    public void mouseDown(MouseEvent arg0) {
      // ignored
    }

    @Override
    public void mouseUp(MouseEvent arg0) {
      currentDataSet.dragPoint = null;
    }

    @Override
    public void mouseScrolled(MouseEvent me) {
      GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
      double newZoomFactor;
      if (me.count > 0) {
        if ( dataSet.zoomFactor > 5) {
          // already fully zoomed in
          return;
        }
        // scroll forward / zoom in
        newZoomFactor = dataSet.zoomFactor + .1;
        // TODO - zoom in on mouse position?
      } else {
        if (dataSet.zoomFactor < .8) {
          // already fully zoomed out
          return;
        }
        // scroll back / zoom out
        newZoomFactor = dataSet.zoomFactor - .1;
      }
      int xZoomChange = (int)((mainShell.getSize().x * newZoomFactor) - (mainShell.getSize().x * dataSet.zoomFactor));
      int yZoomChange = (int)((mainShell.getSize().y * newZoomFactor) - (mainShell.getSize().y * dataSet.zoomFactor));
      
      dataSet.zoomFactor = newZoomFactor;
      
      int newX = dataSet.mainOrigin.x;
      int newY = dataSet.mainOrigin.y;
      newX += xZoomChange / 2;
      newY += yZoomChange / 2;
      updateMainOrigin(newX, newY);
    }
  }

  /**
   * <p>Container of data which represents the state of the graph.</p>
   *
   * @author jent - Mike Jensen
   */
  protected static class GraphDataSet {
    protected final Point naturalBounds;
    protected volatile double zoomFactor;
    protected volatile Map<Node, GuiPoint> guiNodeMap;
    protected volatile boolean drawAllNames;
    protected volatile Point mainOrigin;
    private GuiPoint movingPoint;
    private Point dragPoint;
    private GuiPoint highlightedPoint;

    public GraphDataSet(int xSize, int ySize) {
      naturalBounds = new Point(xSize, ySize);
      zoomFactor = 1;
      guiNodeMap = Collections.emptyMap();
      drawAllNames = true;
      mainOrigin = new Point(0, 0);
      movingPoint = null;
      dragPoint = null;
      highlightedPoint = null;
    }

    /**
     * Updates the stored data with the provided guiNodeMap.
     * 
     * @param guiNodeMap New map of nodes and points to store
     * @param headNode Node that the graph starts from
     */
    public void setData(Map<Node, GuiPoint> guiNodeMap, Node headNode) {
      this.guiNodeMap = guiNodeMap;
      drawAllNames = guiNodeMap.size() <= MAX_NODES_DRAW_ALL_NAMES;
      
      // cluster the dots better
      List<Node> childNodes = new ArrayList<Node>();
      for (Node n: headNode.getChildNodes()) {
        childNodes.addAll(n.getChildNodes());
      }
      while (! childNodes.isEmpty()) {
        List<Node> newChildNodes = new ArrayList<Node>();
        Iterator<Node> it = childNodes.iterator();
        while (it.hasNext()) {
          Node childNode = it.next();
          GuiPoint childGp = guiNodeMap.get(childNode);
          if (childGp == null) {
            System.err.println("***** unknown node: " + childNode.getName() + " *****");
            continue;
          }
          int sampleSize = 0;
          int totalParentPos = 0;
          Iterator<Node> pIt = childNode.getParentNodes().iterator();
          List<Node> toRemoveParents = new ArrayList<Node>();
          while (pIt.hasNext()) {
            Node pNode = pIt.next();
            GuiPoint gp = guiNodeMap.get(pNode);
            if (gp == null) {
              /* This is rather common from node deletions which result in no way to get to them 
               * via child chains, for now we just clean this up as we find it.
               */
              toRemoveParents.add(pNode);
              continue;
            }
            sampleSize++;
            totalParentPos += gp.getY();
          }
          for (Node pNode: toRemoveParents) {
            childNode.removeParentNode(pNode);
          }
          if (sampleSize > 0) {
            int moveDistance = ((totalParentPos / sampleSize) - childGp.getY()) / SQUEEZE_FACTOR;
            childGp.position.y += moveDistance;
          }
          for (Node n: childNode.getChildNodes()) {
            if (! newChildNodes.contains(n)) {
              newChildNodes.add(n);
            }
          }
        }
        childNodes = newChildNodes;
      }
    }
  }

  /**
   * <p>Class which stores information for used for drawing a node on the GUI.</p>
   *
   * @author jent - Mike Jensen
   */
  protected static class GuiPoint {
    protected final Color color;
    protected final Point mainBounds;
    protected Map<Integer, List<GuiPoint>> xRegionCountMap;
    protected int xRegion;
    protected int yRegion;
    protected boolean coordiantesSet;
    protected Point position;

    public GuiPoint(Color color, Point mainBounds,
                    Map<Integer, List<GuiPoint>> xRegionCountMap, int xRegion, int yRegion) {
      this.color = color;
      this.mainBounds = mainBounds;
      this.xRegionCountMap = xRegionCountMap;
      this.xRegion = xRegion;
      this.yRegion = yRegion;
      coordiantesSet = false;
      position = null;
    }

    private void ensureCoordinatesSet() {
      if (! coordiantesSet) {
        coordiantesSet = true;
        int x, y;
        if (xRegion == 1) {
          x = DISTANCE_FROM_EDGE;
        } else {
          x = getSoftGridPoint(xRegion, xRegionCountMap.size(), mainBounds.x);
        }
        y = getSoftGridPoint(yRegion, xRegionCountMap.get(xRegion).size(), mainBounds.y);
        position = new Point(x, y);
        xRegionCountMap = null; // no longer needed, allow GC
      }
    }

    public int getX() {
      ensureCoordinatesSet();
      return position.x;
    }

    public int getY() {
      ensureCoordinatesSet();
      return position.y;
    }

    public void setPosition(int x, int y) {
      coordiantesSet = true;
      position = new Point(x, y);
    }
  }
}
