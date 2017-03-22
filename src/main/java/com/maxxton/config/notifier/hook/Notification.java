package com.maxxton.config.notifier.hook;

import java.util.HashSet;
import java.util.Set;

/**
 * Notification class
 *
 * Holds branch information and changed files after a push or merge.
 *
 * Maxxton Group 2017
 *
 * @author R. Hermans (r.hermans@maxxton.com)
 */
public class Notification {

  private String branch;
  private Set<String> files;

  public Notification() {
    this.branch = "";
    this.files = new HashSet<String>();
  }

  public String getBranch() {
    return branch;
  }

  public void setBranch(String branch) {
    this.branch = branch;
  }

  public void addFile(String file) {
    this.files.add(file);
  }

  public Set<String> getFiles() {
    return files;
  }

  public void setFiles(Set<String> files) {
    this.files = files;
  }
}
