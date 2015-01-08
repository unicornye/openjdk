/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.internal.jimage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ImageReader extends BasicImageReader {
    // well-known strings needed for image file system.
    static final UTF8String ROOT_STRING = new UTF8String("/");

    // attributes of the .jimage file. jimage file does not contain
    // attributes for the individual resources (yet). We use attributes
    // of the jimage file itself (creation, modification, access times).
    // Iniitalized lazily, see {@link #imageFileAttributes()}.
    private BasicFileAttributes imageFileAttributes;

    private final ImageModuleData moduleData;

    // directory management implementation
    private final Map<UTF8String, Node> nodes;
    private volatile Directory rootDir;

    ImageReader(String imagePath, ByteOrder byteOrder) throws IOException {
        super(imagePath, byteOrder);
        this.moduleData = new ImageModuleData(this);
        this.nodes = Collections.synchronizedMap(new HashMap<>());
    }

    ImageReader(String imagePath) throws IOException {
        this(imagePath, ByteOrder.nativeOrder());
    }

    public static ImageReader open(String imagePath, ByteOrder byteOrder) throws IOException {
        return new ImageReader(imagePath, byteOrder);
    }

    /**
     * Opens the given file path as an image file, returning an {@code ImageReader}.
     */
    public static ImageReader open(String imagePath) throws IOException {
        return open(imagePath, ByteOrder.nativeOrder());
    }

    @Override
    public synchronized void close() throws IOException {
        super.close();
        clearNodes();
    }

    @Override
    public ImageLocation findLocation(UTF8String name) {
        ImageLocation location = super.findLocation(name);

        // NOTE: This should be removed when module system is up in full.
        if (location == null) {
            int index = name.lastIndexOf('/');

            if (index != -1) {
                UTF8String packageName = name.substring(0, index);
                UTF8String moduleName = moduleData.packageToModule(packageName);

                if (moduleName != null) {
                    UTF8String fullName = UTF8String.SLASH_STRING.concat(moduleName,
                            UTF8String.SLASH_STRING, name);
                    location = super.findLocation(fullName);
                }
            }
        }

        return location;
    }

    /**
     * Return the module name that contains the given package name.
     */
    public String getModule(String packageName) {
        return moduleData.packageToModule(packageName);
    }

    // jimage file does not store directory structure. We build nodes
    // using the "path" strings found in the jimage file.
    // Node can be a directory or a resource
    public static abstract class Node {
        private static final int ROOT_DIR = 0b0000_0000_0000_0001;
        private static final int MODULE_DIR = 0b0000_0000_0000_0010;
        private static final int METAINF_DIR = 0b0000_0000_0000_0100;
        private static final int TOPLEVEL_PKG_DIR = 0b0000_0000_0000_1000;
        private static final int HIDDEN = 0b0000_0000_0001_0000;

        private int flags;
        private final UTF8String name;
        private final BasicFileAttributes fileAttrs;

        Node(UTF8String name, BasicFileAttributes fileAttrs) {
            assert name != null;
            assert fileAttrs != null;
            this.name = name;
            this.fileAttrs = fileAttrs;
        }

        public final void setIsRootDir() {
            flags |= ROOT_DIR;
        }

        public final boolean isRootDir() {
            return (flags & ROOT_DIR) != 0;
        }

        public final void setIsModuleDir() {
            flags |= MODULE_DIR;
        }

        public final boolean isModuleDir() {
            return (flags & MODULE_DIR) != 0;
        }

        public final void setIsMetaInfDir() {
            flags |= METAINF_DIR;
        }

        public final boolean isMetaInfDir() {
            return (flags & METAINF_DIR) != 0;
        }

        public final void setIsTopLevelPackageDir() {
            flags |= TOPLEVEL_PKG_DIR;
        }

        public final boolean isTopLevelPackageDir() {
            return (flags & TOPLEVEL_PKG_DIR) != 0;
        }

        public final void setIsHidden() {
            flags |= HIDDEN;
        }

        public final boolean isHidden() {
            return (flags & HIDDEN) != 0;
        }

        public final boolean isVisible() {
            return !isHidden();
        }

        public final UTF8String getName() {
            return name;
        }

        public final BasicFileAttributes getFileAttributes() {
            return fileAttrs;
        }

        public boolean isDirectory() {
            return false;
        }

        public List<Node> getChildren() {
            throw new IllegalArgumentException("not a directory: " + getNameString());
        }

        public boolean isResource() {
            return false;
        }

        public ImageLocation getLocation() {
            throw new IllegalArgumentException("not a resource: " + getNameString());
        }

        public long size() {
            return 0L;
        }

        public long compressedSize() {
            return 0L;
        }

        public String extension() {
            return null;
        }

        public long contentOffset() {
            return 0L;
        }

        public final FileTime creationTime() {
            return fileAttrs.creationTime();
        }

        public final FileTime lastAccessTime() {
            return fileAttrs.lastAccessTime();
        }

        public final FileTime lastModifiedTime() {
            return fileAttrs.lastModifiedTime();
        }

        public final String getNameString() {
            return name.toString();
        }

        @Override
        public final String toString() {
            return getNameString();
        }

        @Override
        public final int hashCode() {
            return name.hashCode();
        }

        @Override
        public final boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (other instanceof Node) {
                return name.equals(((Node) other).name);
            }

            return false;
        }
    }

    // directory node - directory has full path name without '/' at end.
    public static final class Directory extends Node {
        private final List<Node> children;

        @SuppressWarnings("LeakingThisInConstructor")
        Directory(Directory parent, UTF8String name, BasicFileAttributes fileAttrs) {
            super(name, fileAttrs);
            children = new ArrayList<>();
            if (parent != null) {
                parent.addChild(this);
            }
        }

        @Override
        public boolean isDirectory() {
            return true;
        }

        public List<Node> getChildren() {
            return Collections.unmodifiableList(children);
        }

        void addChild(Node node) {
            children.add(node);
        }

        public void walk(Consumer<? super Node> consumer) {
            consumer.accept(this);
            for ( Node child : children ) {
                if (child.isDirectory()) {
                    ((Directory)child).walk(consumer);
                } else {
                    consumer.accept(child);
                }
            }
        }
    }

    // "resource" is .class or any other resource (compressed/uncompressed) in a jimage.
    // full path of the resource is the "name" of the resource.
    public static class Resource extends Node {
        private final ImageLocation loc;

        @SuppressWarnings("LeakingThisInConstructor")
        Resource(Directory parent, ImageLocation loc, BasicFileAttributes fileAttrs) {
            this(parent, loc.getFullName(), loc, fileAttrs);
        }

        @SuppressWarnings("LeakingThisInConstructor")
        Resource(Directory parent, UTF8String name, ImageLocation loc,
                BasicFileAttributes fileAttrs) {
            super(name, fileAttrs);
            this.loc = loc;
            parent.addChild(this);
        }

        @Override
        public boolean isResource() {
            return true;
        }

        @Override
        public ImageLocation getLocation() {
            return loc;
        }

        @Override
        public long size() {
            return loc.getUncompressedSize();
        }

        @Override
        public long compressedSize() {
            return loc.getCompressedSize();
        }

        @Override
        public String extension() {
            return loc.getExtensionString();
        }

        @Override
        public long contentOffset() {
            return loc.getContentOffset();
        }
    }

    // directory management interface
    public Directory getRootDirectory() {
        return buildRootDirectory();
    }

    public Node findNode(String name) {
        return findNode(new UTF8String(name));
    }

    public Node findNode(byte[] name) {
        return findNode(new UTF8String(name));
    }

    public synchronized Node findNode(UTF8String name) {
        buildRootDirectory();
        return nodes.get(name);
    }

    private synchronized void clearNodes() {
        nodes.clear();
        rootDir = null;
    }

    /**
     * Returns the file attributes of the image file.
     */
    private BasicFileAttributes imageFileAttributes() {
        BasicFileAttributes attrs = imageFileAttributes;
        if (attrs == null) {
            try {
                Path file = Paths.get(imagePath());
                attrs = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe);
            }
            imageFileAttributes = attrs;
        }
        return attrs;
    }

    private synchronized Directory buildRootDirectory() {
        if (rootDir != null) {
            return rootDir;
        }

        // FIXME no time information per resource in jimage file (yet?)
        // we use file attributes of jimage itself.
        // root directory
        rootDir = new Directory(null, ROOT_STRING, imageFileAttributes());
        rootDir.setIsRootDir();
        nodes.put(rootDir.getName(), rootDir);

        ImageLocation[] locs = getAllLocations(true);
        for (ImageLocation loc : locs) {
            String module = loc.getModuleString();
            String parent = loc.getParentString();
             // directory where this location goes as child
            Directory dir;
            if (parent.isEmpty()) {
                // top level entry under root
                dir = rootDir;
            } else if (parent.startsWith("META-INF")) {
                dir = makeDirectories(loc.buildName(false, true, false), true);
                dir.setIsMetaInfDir();
            } else if (module.isEmpty()) {
                dir = makeDirectories(loc.buildName(false, true, false), true);
                dir.setIsTopLevelPackageDir();
            } else {
                dir = makeDirectories(loc.buildName(true, false, false), false);
                dir.setIsModuleDir();
                dir = makeDirectories(loc.buildName(true, true, false), false);
            }

            Resource entry = new Resource(dir, loc, imageFileAttributes());
            nodes.put(entry.getName(), entry);

            // NOTE: Remove when jimagefs disappears.
            if (!module.isEmpty() && !parent.isEmpty()) {
                dir = makeDirectories(loc.buildName(false, true, false), true);
                dir.setIsTopLevelPackageDir();
                entry = new Resource(dir, loc, imageFileAttributes());
                nodes.put(loc.buildName(false, true, true), entry);
            }
        }

        return rootDir;
    }

    private Directory newDirectory(Directory parent, UTF8String name) {
        Directory dir = new Directory(parent, name, imageFileAttributes());
        nodes.put(dir.getName(), dir);
        return dir;
    }

    private List<UTF8String> dirs(UTF8String parent) {
        List<UTF8String> splits = new ArrayList<>();

        for (int i = 1; i < parent.length(); i++) {
            if (parent.byteAt(i) == '/') {
                splits.add(parent.substring(0, i));
            }
        }

        splits.add(parent);

        return splits;
    }

    private Directory makeDirectories(UTF8String parent, boolean isHidden) {
        Directory last = rootDir;
        List<UTF8String> dirs = dirs(parent);

        for (UTF8String dir : dirs) {
            Directory nextDir = (Directory) nodes.get(dir);
            if (nextDir == null) {
                nextDir = newDirectory(last, dir);

                if (isHidden) {
                    nextDir.setIsHidden();
                }
            }
            last = nextDir;
        }

        return last;
    }

    public byte[] getResource(Node node) throws IOException {
        if (node.isResource()) {
            return super.getResource(node.getLocation());
        }
        throw new IOException("Not a resource: " + node);
    }

    public byte[] getResource(Resource rs) throws IOException {
        return super.getResource(rs.getLocation());
    }
}
