package com.logicvoid0x.billing.gateway;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Routes an org to its cell of gateway hosts in a region (design: "Each org is routed to a cell of
 * 8–32 hosts per region"), so an org's leases live on few hosts and stay warm.
 *
 * <p>Consistent hashing with virtual nodes: each host owns {@code virtualNodes} points on a 64-bit
 * ring, and an org's cell is the first {@code cellSize} distinct hosts clockwise from the org's
 * point. Adding or removing one host moves only about {@code 1/n} of the cell slots, and every
 * gateway computes the same cells (SHA-256, not {@code hashCode}).
 */
public final class CellRouter {
  /** Smallest cell the design allows. */
  public static final int MIN_CELL = 8;

  /** Largest cell the design allows. */
  public static final int MAX_CELL = 32;

  private final NavigableMap<Long, String> ring = new TreeMap<>();
  private final int cellSize;
  private final int hosts;

  /**
   * Creates the router.
   *
   * @param hosts the region's gateway hosts
   * @param cellSize hosts per cell, 8 to 32 (capped at the number of hosts)
   * @param virtualNodes ring points per host
   */
  public CellRouter(Collection<String> hosts, int cellSize, int virtualNodes) {
    if (cellSize < MIN_CELL || cellSize > MAX_CELL) {
      throw new IllegalArgumentException("cell size must be 8 to 32, got " + cellSize);
    }
    if (hosts.isEmpty() || virtualNodes < 1) {
      throw new IllegalArgumentException("need at least one host and one virtual node");
    }
    Set<String> unique = new LinkedHashSet<>(hosts);
    for (String host : unique) {
      for (int v = 0; v < virtualNodes; v++) {
        ring.put(hash(host + "#" + v), host);
      }
    }
    this.hosts = unique.size();
    this.cellSize = Math.min(cellSize, this.hosts);
  }

  /** The hosts of {@code orgId}'s cell, in ring order (the first is the org's primary). */
  public List<String> cell(String orgId) {
    List<String> cell = new ArrayList<>(cellSize);
    long point = hash("org:" + orgId);
    for (Map.Entry<Long, String> e : ring.tailMap(point, true).entrySet()) {
      if (add(cell, e.getValue())) {
        return cell;
      }
    }
    for (Map.Entry<Long, String> e : ring.entrySet()) { // wrap around
      if (add(cell, e.getValue())) {
        return cell;
      }
    }
    return cell;
  }

  /** The host in {@code orgId}'s cell that serves {@code requestId} (spreads load in the cell). */
  public String host(String orgId, String requestId) {
    List<String> cell = cell(orgId);
    return cell.get((int) Math.floorMod(hash("req:" + requestId), (long) cell.size()));
  }

  /** Hosts on the ring. */
  public int hosts() {
    return hosts;
  }

  private boolean add(List<String> cell, String host) {
    if (!cell.contains(host)) {
      cell.add(host);
    }
    return cell.size() == cellSize;
  }

  static long hash(String key) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.wrap(d, 0, 8).getLong();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }
}
