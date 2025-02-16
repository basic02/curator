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

package org.apache.curator.utils;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import java.util.Collections;
import java.util.List;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZKPaths {
    /**
     * Zookeeper's path separator character.
     */
    public static final String PATH_SEPARATOR = "/";

    private static final char PATH_SEPARATOR_CHAR = '/';

    private static final CreateMode NON_CONTAINER_MODE = CreateMode.PERSISTENT;

    /**
     * @return {@link CreateMode#CONTAINER} if the ZK JAR supports it. Otherwise {@link CreateMode#PERSISTENT}
     */
    public static CreateMode getContainerCreateMode() {
        return CreateModeHolder.containerCreateMode;
    }

    /**
     * Returns true if the version of ZooKeeper client in use supports containers
     *
     * @return true/false
     */
    public static boolean hasContainerSupport() {
        return getContainerCreateMode() != NON_CONTAINER_MODE;
    }

    private static class CreateModeHolder {
        private static final Logger log = LoggerFactory.getLogger(ZKPaths.class);
        private static final CreateMode containerCreateMode;

        static {
            CreateMode localCreateMode;
            try {
                localCreateMode = CreateMode.valueOf("CONTAINER");
            } catch (IllegalArgumentException ignore) {
                localCreateMode = NON_CONTAINER_MODE;
                log.warn(
                        "The version of ZooKeeper being used doesn't support Container nodes. CreateMode.PERSISTENT will be used instead.");
            }
            containerCreateMode = localCreateMode;
        }
    }

    /**
     * Apply the namespace to the given path
     *
     * @param namespace namespace (can be null)
     * @param path      path
     * @return adjusted path
     */
    public static String fixForNamespace(String namespace, String path) {
        return fixForNamespace(namespace, path, false);
    }

    /**
     * Apply the namespace to the given path
     *
     * @param namespace    namespace (can be null)
     * @param path         path
     * @param isSequential if the path is being created with a sequential flag
     * @return adjusted path
     */
    public static String fixForNamespace(String namespace, String path, boolean isSequential) {
        // Child path must be valid in and of itself.
        PathUtils.validatePath(path, isSequential);

        if (namespace != null) {
            return makePath(namespace, path);
        }
        return path;
    }

    /**
     * Given a full path, return the node name. i.e. "/one/two/three" will return "three"
     *
     * @param path the path
     * @return the node
     */
    public static String getNodeFromPath(String path) {
        PathUtils.validatePath(path);
        int i = path.lastIndexOf(PATH_SEPARATOR_CHAR);
        if (i < 0) {
            return path;
        }
        if ((i + 1) >= path.length()) {
            return "";
        }
        return path.substring(i + 1);
    }

    public static class PathAndNode {
        private final String path;
        private final String node;

        public PathAndNode(String path, String node) {
            this.path = path;
            this.node = node;
        }

        public String getPath() {
            return path;
        }

        public String getNode() {
            return node;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + node.hashCode();
            result = prime * result + path.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            PathAndNode other = (PathAndNode) obj;
            if (!node.equals(other.node)) {
                return false;
            }
            if (!path.equals(other.path)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "PathAndNode [path=" + path + ", node=" + node + "]";
        }
    }

    /**
     * Given a full path, return the node name and its path. i.e. "/one/two/three" will return {"/one/two", "three"}
     *
     * @param path the path
     * @return the node
     */
    public static PathAndNode getPathAndNode(String path) {
        PathUtils.validatePath(path);
        int i = path.lastIndexOf(PATH_SEPARATOR_CHAR);
        if (i < 0) {
            return new PathAndNode(path, "");
        }
        if ((i + 1) >= path.length()) {
            return new PathAndNode(PATH_SEPARATOR, "");
        }
        String node = path.substring(i + 1);
        String parentPath = (i > 0) ? path.substring(0, i) : PATH_SEPARATOR;
        return new PathAndNode(parentPath, node);
    }

    // Hardcoded in {@link org.apache.zookeeper.server.PrepRequestProcessor}
    static final int SEQUENTIAL_SUFFIX_DIGITS = 10;

    /**
     * Extracts the ten-digit suffix from a sequential znode path. Does not currently perform validation on the
     * provided path; it will just return a string comprising the last ten characters.
     *
     * @param path the path of a sequential znodes
     * @return the sequential suffix
     */
    public static String extractSequentialSuffix(String path) {
        int length = path.length();
        return length > SEQUENTIAL_SUFFIX_DIGITS ? path.substring(length - SEQUENTIAL_SUFFIX_DIGITS) : path;
    }

    private static final Splitter PATH_SPLITTER =
            Splitter.on(PATH_SEPARATOR_CHAR).omitEmptyStrings();

    /**
     * Given a full path, return the the individual parts, without slashes.
     * The root path will return an empty list.
     *
     * @param path the path
     * @return an array of parts
     */
    public static List<String> split(String path) {
        PathUtils.validatePath(path);
        return PATH_SPLITTER.splitToList(path);
    }

    /**
     * Make sure all the nodes in the path are created. NOTE: Unlike File.mkdirs(), Zookeeper doesn't distinguish
     * between directories and files. So, every node in the path is created. The data for each node is an empty blob
     *
     * @param zookeeper the client
     * @param path      path to ensure
     * @throws InterruptedException                 thread interruption
     * @throws org.apache.zookeeper.KeeperException Zookeeper errors
     */
    public static void mkdirs(ZooKeeper zookeeper, String path) throws InterruptedException, KeeperException {
        mkdirs(zookeeper, path, true, null, false);
    }

    /**
     * Make sure all the nodes in the path are created. NOTE: Unlike File.mkdirs(), Zookeeper doesn't distinguish
     * between directories and files. So, every node in the path is created. The data for each node is an empty blob
     *
     * @param zookeeper    the client
     * @param path         path to ensure
     * @param makeLastNode if true, all nodes are created. If false, only the parent nodes are created
     * @throws InterruptedException                 thread interruption
     * @throws org.apache.zookeeper.KeeperException Zookeeper errors
     */
    public static void mkdirs(ZooKeeper zookeeper, String path, boolean makeLastNode)
            throws InterruptedException, KeeperException {
        mkdirs(zookeeper, path, makeLastNode, null, false);
    }

    /**
     * Make sure all the nodes in the path are created. NOTE: Unlike File.mkdirs(), Zookeeper doesn't distinguish
     * between directories and files. So, every node in the path is created. The data for each node is an empty blob
     *
     * @param zookeeper    the client
     * @param path         path to ensure
     * @param makeLastNode if true, all nodes are created. If false, only the parent nodes are created
     * @param aclProvider  if not null, the ACL provider to use when creating parent nodes
     * @throws InterruptedException                 thread interruption
     * @throws org.apache.zookeeper.KeeperException Zookeeper errors
     */
    public static void mkdirs(ZooKeeper zookeeper, String path, boolean makeLastNode, InternalACLProvider aclProvider)
            throws InterruptedException, KeeperException {
        mkdirs(zookeeper, path, makeLastNode, aclProvider, false);
    }

    /**
     * Make sure all the nodes in the path are created. NOTE: Unlike File.mkdirs(), Zookeeper doesn't distinguish
     * between directories and files. So, every node in the path is created. The data for each node is an empty blob
     *
     * @param zookeeper    the client
     * @param path         path to ensure
     * @param makeLastNode if true, all nodes are created. If false, only the parent nodes are created
     * @param aclProvider  if not null, the ACL provider to use when creating parent nodes
     * @param asContainers if true, nodes are created as {@link CreateMode#CONTAINER}
     * @throws InterruptedException                 thread interruption
     * @throws org.apache.zookeeper.KeeperException Zookeeper errors
     */
    public static void mkdirs(
            ZooKeeper zookeeper,
            final String path,
            boolean makeLastNode,
            InternalACLProvider aclProvider,
            boolean asContainers)
            throws InterruptedException, KeeperException {
        PathUtils.validatePath(path);

        // This first loop locates the first node that doesn't exist from leaf nodes towards root
        // this way, it is not required to have read access on all parents.
        // This is relevant after https://issues.apache.org/jira/browse/ZOOKEEPER-2590.

        int pos = path.length();
        do {
            String subPath = path.substring(0, pos);
            if (zookeeper.exists(subPath, false) != null) {
                break;
            }
            pos = path.lastIndexOf(PATH_SEPARATOR_CHAR, pos - 1);
        } while (pos > 0);

        // Start creating the subtree after the longest path that exists, assuming root always exists.

        while (pos < path.length()) {
            pos = path.indexOf(PATH_SEPARATOR_CHAR, pos + 1);

            if (pos == -1) {
                if (makeLastNode) {
                    pos = path.length();
                } else {
                    break;
                }
            }

            String subPath = path.substring(0, pos);

            // All the paths from the initial `pos` do not exist.
            try {
                List<ACL> acl = null;
                if (aclProvider != null) {
                    acl = aclProvider.getAclForPath(subPath);
                    if (acl == null) {
                        acl = aclProvider.getDefaultAcl();
                    }
                }
                if (acl == null) {
                    acl = ZooDefs.Ids.OPEN_ACL_UNSAFE;
                }
                zookeeper.create(subPath, new byte[0], acl, getCreateMode(asContainers));
            } catch (KeeperException.NodeExistsException ignore) {
                // ignore... someone else has created it since we checked
            }
        }
        ;
    }

    /**
     * Recursively deletes children of a node.
     *
     * @param zookeeper  the client
     * @param path       path of the node to delete
     * @param deleteSelf flag that indicates that the node should also get deleted
     * @throws InterruptedException
     * @throws KeeperException
     */
    public static void deleteChildren(ZooKeeper zookeeper, String path, boolean deleteSelf)
            throws InterruptedException, KeeperException {
        PathUtils.validatePath(path);

        List<String> children;
        try {
            children = zookeeper.getChildren(path, null);
        } catch (KeeperException.NoNodeException e) {
            // someone else has deleted the node since we checked
            return;
        }
        for (String child : children) {
            String fullPath = makePath(path, child);
            deleteChildren(zookeeper, fullPath, true);
        }

        if (deleteSelf) {
            try {
                zookeeper.delete(path, -1);
            } catch (KeeperException.NotEmptyException e) {
                // someone has created a new child since we checked ... delete again.
                deleteChildren(zookeeper, path, true);
            } catch (KeeperException.NoNodeException e) {
                // ignore... someone else has deleted the node since we checked
            }
        }
    }

    /**
     * Return the children of the given path sorted by sequence number
     *
     * @param zookeeper the client
     * @param path      the path
     * @return sorted list of children
     * @throws InterruptedException                 thread interruption
     * @throws org.apache.zookeeper.KeeperException zookeeper errors
     */
    public static List<String> getSortedChildren(ZooKeeper zookeeper, String path)
            throws InterruptedException, KeeperException {
        List<String> children = zookeeper.getChildren(path, false);
        List<String> sortedList = Lists.newArrayList(children);
        Collections.sort(sortedList);
        return sortedList;
    }

    /**
     * Given a parent path and a child node, create a combined full path
     *
     * @param parent the parent
     * @param child  the child
     * @return full path
     */
    public static String makePath(String parent, String child) {
        // 2 is the maximum number of additional path separators inserted
        int maxPathLength = nullableStringLength(parent) + nullableStringLength(child) + 2;
        // Avoid internal StringBuilder's buffer reallocation by specifying the max path length
        StringBuilder path = new StringBuilder(maxPathLength);

        joinPath(path, parent, child);

        return path.toString();
    }

    /**
     * Given a parent path and a list of children nodes, create a combined full path
     *
     * @param parent       the parent
     * @param firstChild   the first children in the path
     * @param restChildren the rest of the children in the path
     * @return full path
     */
    public static String makePath(String parent, String firstChild, String... restChildren) {
        // 2 is the maximum number of additional path separators inserted
        int maxPathLength = nullableStringLength(parent) + nullableStringLength(firstChild) + 2;
        if (restChildren != null) {
            for (String child : restChildren) {
                // 1 is for possible additional separator
                maxPathLength += nullableStringLength(child) + 1;
            }
        }
        // Avoid internal StringBuilder's buffer reallocation by specifying the max path length
        StringBuilder path = new StringBuilder(maxPathLength);

        joinPath(path, parent, firstChild);

        if (restChildren == null) {
            return path.toString();
        } else {
            for (String child : restChildren) {
                joinPath(path, "", child);
            }

            return path.toString();
        }
    }

    private static int nullableStringLength(String s) {
        return s != null ? s.length() : 0;
    }

    /**
     * Given a parent and a child node, join them in the given {@link StringBuilder path}
     *
     * @param path   the {@link StringBuilder} used to make the path
     * @param parent the parent
     * @param child  the child
     */
    private static void joinPath(StringBuilder path, String parent, String child) {
        // Add parent piece, with no trailing slash.
        if ((parent != null) && (parent.length() > 0)) {
            if (parent.charAt(0) != PATH_SEPARATOR_CHAR) {
                path.append(PATH_SEPARATOR_CHAR);
            }
            if (parent.charAt(parent.length() - 1) == PATH_SEPARATOR_CHAR) {
                path.append(parent, 0, parent.length() - 1);
            } else {
                path.append(parent);
            }
        }

        if ((child == null)
                || (child.length() == 0)
                || (child.length() == 1 && child.charAt(0) == PATH_SEPARATOR_CHAR)) {
            // Special case, empty parent and child
            if (path.length() == 0) {
                path.append(PATH_SEPARATOR_CHAR);
            }
            return;
        }

        // Now add the separator between parent and child.
        path.append(PATH_SEPARATOR_CHAR);

        int childAppendBeginIndex;
        if (child.charAt(0) == PATH_SEPARATOR_CHAR) {
            childAppendBeginIndex = 1;
        } else {
            childAppendBeginIndex = 0;
        }

        int childAppendEndIndex;
        if (child.charAt(child.length() - 1) == PATH_SEPARATOR_CHAR) {
            childAppendEndIndex = child.length() - 1;
        } else {
            childAppendEndIndex = child.length();
        }

        // Finally, add the child.
        path.append(child, childAppendBeginIndex, childAppendEndIndex);
    }

    private ZKPaths() {}

    private static CreateMode getCreateMode(boolean asContainers) {
        return asContainers ? getContainerCreateMode() : CreateMode.PERSISTENT;
    }
}
