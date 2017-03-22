package com.maxxton.config.notifier.hook;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.atlassian.bitbucket.commit.Changeset;
import com.atlassian.bitbucket.commit.ChangesetsRequest;
import com.atlassian.bitbucket.commit.CommitService;
import com.atlassian.bitbucket.content.Change;
import com.atlassian.bitbucket.content.Path;
import com.atlassian.bitbucket.hook.repository.AsyncPostReceiveRepositoryHook;
import com.atlassian.bitbucket.hook.repository.RepositoryHookContext;
import com.atlassian.bitbucket.repository.MinimalRef;
import com.atlassian.bitbucket.repository.RefChange;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.setting.RepositorySettingsValidator;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.setting.SettingsValidationErrors;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequest;
import com.atlassian.bitbucket.util.PageRequestImpl;
import com.atlassian.plugin.spring.scanner.annotation.component.Scanned;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.google.gson.Gson;

/**
 * Config Notifier post pull request hook
 *
 * Triggers a post request to the config server to notify about property changes
 *
 * Maxxton Group 2017
 *
 * @author R. Hermans (r.hermans@maxxton.com)
 */
@Scanned
public class ConfigNotifierHook implements AsyncPostReceiveRepositoryHook, RepositorySettingsValidator {

  private final CommitService commitService;

  @Autowired
  public ConfigNotifierHook(@ComponentImport CommitService commitService) {
    this.commitService = commitService;
  }

  /**
   * Create and send notifications after a push or pull request.
   *
   * @param context    context of the RepositoryHook
   * @param refChanges Collections of changes with reference ids and hashes
   */
  public void postReceive(RepositoryHookContext context, Collection<RefChange> refChanges) {
    String development = context.getSettings().getString("url-development");
    String acceptance = context.getSettings().getString("url-acceptance");
    String production = context.getSettings().getString("url-production");

    if (development != null && acceptance != null && production != null) {

      Notification notification = new Notification();
      Set<String> commits = new HashSet<String>();
      for (RefChange change : refChanges) {
        commits.add(change.getToHash());
        if (notification.getBranch() == null || notification.getBranch() == "") {
          MinimalRef ref = change.getRef();
          String branch = ref.getDisplayId();
          notification.setBranch(branch);
        }
      }

      for (String commitId : commits) {
        ChangesetsRequest changesetsRequest = new ChangesetsRequest.Builder(context.getRepository()).commitId(commitId).build();
        PageRequest request = new PageRequestImpl(0, 100);
        Page<Changeset> changePage = this.commitService.getChangesets(changesetsRequest, request);
        for (Changeset changeset : changePage.getValues()) {
          for (Change change : changeset.getChanges().getValues()) {
            Path path = change.getPath();
            notification.addFile(path.getName());
          }
        }
      }

      if (notification.getBranch().equals("develop")) {
        this.sendNotification(development, notification);
      }
      if (notification.getBranch().equals("master")) {
        this.sendNotification(acceptance, notification);
        this.sendNotification(production, notification);
      }
    }
  }

  /**
   * Send a notification to a config server
   *
   * @param url          url of the config server
   * @param notification notification containing the branch and changed files
   */
  private void sendNotification(String url, Notification notification) {
    Gson gson = new Gson();
    String object = gson.toJson(notification);

    url = url.endsWith("/") ? url + "monitor" : url + "/monitor";

    CloseableHttpClient client = null;
    try {
      client = HttpClients.createDefault();
      HttpPost httpPost = new HttpPost(url);
      StringEntity entity = new StringEntity(object);
      httpPost.setEntity(entity);
      httpPost.setHeader("Accept", "application/json");
      httpPost.setHeader("Content-type", "application/json");
      httpPost.setHeader("X-Bitbucket-Event", "merge");
      client.execute(httpPost);
    }
    catch (Exception e) {
      e.printStackTrace();
    }
    finally {
      try {
        client.close();
      }
      catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * Validate the urls set in the config form
   *
   * @param settings   settings retrieved from the form
   * @param errors     holder for errors to be shown to the user
   * @param repository current repository information
   */
  public void validate(Settings settings, SettingsValidationErrors errors, Repository repository) {
    if (settings.getString("url-development", "").isEmpty()) {
      errors.addFieldError("url-development", "Url field for development is blank, please supply one");
    }

    if (settings.getString("url-acceptance", "").isEmpty()) {
      errors.addFieldError("url-acceptance", "Url field for acceptance is blank, please supply one");
    }

    if (settings.getString("url-production", "").isEmpty()) {
      errors.addFieldError("url-production", "Url field for production is blank, please supply one");
    }
  }
}