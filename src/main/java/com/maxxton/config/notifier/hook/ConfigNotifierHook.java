package com.maxxton.config.notifier.hook;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
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
    String developmentUsername = context.getSettings().getString("development-username", "");
    String developmentPassword = context.getSettings().getString("development-password", "");
    String developmentUrl = context.getSettings().getString("development-url", "");

    String acceptanceUsername = context.getSettings().getString("acceptance-username", "");
    String acceptancePassword = context.getSettings().getString("acceptance-password", "");
    String acceptanceUrl = context.getSettings().getString("acceptance-url", "");

    String productionUsername = context.getSettings().getString("production-username", "");
    String productionPassword = context.getSettings().getString("production-password", "");
    String productionUrl = context.getSettings().getString("production-url", "");

    if (!developmentUrl.isEmpty() && !acceptanceUrl.isEmpty() && !productionUrl.isEmpty()) {
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
        this.sendNotification(developmentUsername, developmentPassword, developmentUrl, notification);
      }
      if (notification.getBranch().equals("master")) {
        this.sendNotification(acceptanceUsername, acceptancePassword, acceptanceUrl, notification);
        this.sendNotification(productionUsername, productionPassword, productionUrl, notification);
      }
    }
  }

  /**
   * Send a notification to a config server
   *
   * @param url          url of the config server
   * @param notification notification containing the branch and changed files
   */
  private void sendNotification(String username, String password, String url, Notification notification) {
    Gson gson = new Gson();
    String object = gson.toJson(notification);

    if (!url.startsWith("http://") || !url.startsWith("https://"))
      url = "http://" + url;
    
    url = url.endsWith("/") ? url + "monitor" : url + "/monitor";

    CloseableHttpClient client;

    if (!username.isEmpty() && !password.isEmpty()) {
      CredentialsProvider provider = new BasicCredentialsProvider();
      UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(username, password);
      provider.setCredentials(AuthScope.ANY, credentials);

      client = HttpClientBuilder.create().setDefaultCredentialsProvider(provider).build();
    }
    else {
      client = HttpClients.createDefault();
    }

    try {
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
    // Development validation
    if (settings.getString("development-username", "").isEmpty() && !settings.getString("development-password", "").isEmpty()) {
      errors.addFieldError("development-username", "Username should not be blank when the password is set, please supply one");
    }

    if (!settings.getString("development-username", "").isEmpty() && settings.getString("development-password", "").isEmpty()) {
      errors.addFieldError("development-password", "Password should not be blank when the username is set, please supply one");
    }

    if (settings.getString("development-url", "").isEmpty()) {
      errors.addFieldError("development-url", "Url field for development is blank, please supply one");
    }

    // Acceptance validation
    if (settings.getString("acceptance-username", "").isEmpty() && !settings.getString("acceptance-password", "").isEmpty()) {
      errors.addFieldError("acceptance-username", "Username should not be blank when the password is set, please supply one");
    }

    if (!settings.getString("acceptance-username", "").isEmpty() && settings.getString("acceptance-password", "").isEmpty()) {
      errors.addFieldError("acceptance-password", "Password should not be blank when the username is set, please supply one");
    }

    if (settings.getString("acceptance-url", "").isEmpty()) {
      errors.addFieldError("acceptance-url", "Url field for acceptance is blank, please supply one");
    }

    // Production validation
    if (settings.getString("production-username", "").isEmpty() && !settings.getString("production-password", "").isEmpty()) {
      errors.addFieldError("production-username", "Username should not be blank when the password is set, please supply one");
    }

    if (!settings.getString("production-username", "").isEmpty() && settings.getString("production-password", "").isEmpty()) {
      errors.addFieldError("production-password", "Password should not be blank when the username is set, please supply one");
    }

    if (settings.getString("production-url", "").isEmpty()) {
      errors.addFieldError("production-url", "Url field for production is blank, please supply one");
    }
  }
}