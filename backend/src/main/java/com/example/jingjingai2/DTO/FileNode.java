package com.example.jingjingai2.DTO;

/**
 * 工作区文件节点（用于前端渲染文件树）。
 */
public class FileNode {
    private String path;   // 相对工作区根的路径，如 src/Main.java
    private String name;   // 文件名
    private boolean dir;   // 是否目录
    private long size;     // 字节数

    public FileNode() {}
    public FileNode(String path, String name, boolean dir, long size) {
        this.path = path; this.name = name; this.dir = dir; this.size = size;
    }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isDir() { return dir; }
    public void setDir(boolean dir) { this.dir = dir; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
}
