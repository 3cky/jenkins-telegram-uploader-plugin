/*
 * The MIT License
 *
 * Copyright (c) 2017 Victor Antonovich <v.antonovich@gmail.com>
 *
 * ----------------------------------------------------------------------
 * Permission  is hereby granted, free of charge, to any person obtaining
 * a  copy  of  this  software  and  associated  documentation files (the
 * "Software"),  to  deal  in the Software without restriction, including
 * without  limitation  the  rights to use, copy, modify, merge, publish,
 * distribute,  sublicense,  and/or  sell  copies of the Software, and to
 * permit  persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The  above  copyright  notice  and  this  permission  notice  shall be
 * included  in  all  copies  or  substantial  portions  of the Software.
 *                        -----------------------
 * THE  SOFTWARE  IS  PROVIDED  "AS  IS",  WITHOUT  WARRANTY OF ANY KIND,
 * EXPRESS  OR  IMPLIED,  INCLUDING  BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN  NO  EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * CLAIM,  DAMAGES  OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT  OR  OTHERWISE,  ARISING  FROM,  OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE   OR   THE   USE   OR   OTHER   DEALINGS  IN  THE  SOFTWARE.
 */

package jenkins.plugins.telegramuploader;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import jenkins.util.VirtualFile;
import net.sf.json.JSONObject;

/**
 * @author Victor Antonovich (v.antonovich@gmail.com)
 */
public class TelegramUploader extends Notifier {

    private String chatId;
    private String caption;
    private String filter;
    private boolean silent;
    private boolean failBuildIfUploadFailed;

    @DataBoundConstructor
    public TelegramUploader(String chatId, String caption, String filter, boolean silent,
            boolean failBuildIfUploadFailed) {
        this.chatId = chatId;
        this.filter = filter;
        this.caption = caption;
        this.silent = silent;
        this.failBuildIfUploadFailed = failBuildIfUploadFailed;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public boolean isSilent() {
        return silent;
    }

    public void setSilent(boolean silent) {
        this.silent = silent;
    }

    public boolean isFailBuildIfUploadFailed() {
        return failBuildIfUploadFailed;
    }

    public void setFailBuildIfUploadFailed(boolean failBuildIfUploadFailed) {
        this.failBuildIfUploadFailed = failBuildIfUploadFailed;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public TelegramUploaderDescriptor getDescriptor() {
        return (TelegramUploaderDescriptor) super.getDescriptor();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        PrintStream logger = listener.getLogger();

        boolean failResult = !this.failBuildIfUploadFailed;

        Result buildResult = build.getResult();

        if (buildResult == null) {
            logger.println("Skipping artifacts uploading to the Telegram because no build result found");
            return failResult;
        }

        if (buildResult.isWorseOrEqualTo(Result.FAILURE)) {
            logger.println("Skipping artifacts uploading to the Telegram because of build failure");
            return failResult;
        }

        String artifactsGlob = Util.fixEmptyAndTrim(this.filter);
        if (artifactsGlob == null || artifactsGlob.isEmpty()) {
            artifactsGlob = "**";
        }

        VirtualFile artifactsRoot = build.getArtifactManager().root();
        String[] artifacts = null;
        try {
            artifacts = artifactsRoot.list(artifactsGlob);
        } catch (IOException e) {
            logger.println("Can't get artifacts list to upload to the Telegram, see error below:");
            e.printStackTrace(logger);
            return failResult;
        }

        if (artifacts.length == 0) {
            logger.println("No files are matched by given filter found for upload to the Telegram");
            return failResult;
        }

        String expandedCaption = Util.fixEmptyAndTrim(this.caption);
        if (expandedCaption != null) {
            try {
                expandedCaption = build.getEnvironment(listener).expand(expandedCaption);
            } catch (Exception e) {
                logger.println("Can't expand Telegram document caption '" + expandedCaption +
                        "', see error below:");
                e.printStackTrace(logger);
                return failResult;
            }
        }

        TelegramUploaderDescriptor descriptor = getDescriptor();

        HttpHost httpProxy = null;

        CredentialsProvider httpProxyCredsProvider = new BasicCredentialsProvider();

        String httpProxyUri = descriptor.getHttpProxyUri();
        if (httpProxyUri != null && !httpProxyUri.isEmpty()) {
            try {
                URI proxyUri = URI.create(httpProxyUri);
                httpProxy = new HttpHost(proxyUri.getHost(), proxyUri.getPort(),
                        proxyUri.getScheme());
                String httpProxyUser = descriptor.getHttpProxyUser();
                if (httpProxyUser != null && !httpProxyUser.trim().isEmpty()) {
                    httpProxyCredsProvider.setCredentials(
                            new AuthScope(proxyUri.getHost(), proxyUri.getPort()),
                            new UsernamePasswordCredentials(httpProxyUser.trim(),
                                    descriptor.getHttpProxyPassword()));
                }
            } catch (Exception e) {
                logger.println("Can't set up HTTP proxy, see error below:");
                e.printStackTrace(logger);
                return failResult;
            }
        }

        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultCredentialsProvider(httpProxyCredsProvider).build()) {
            for (String artifact : artifacts) {
                VirtualFile artifactVirtualFile = artifactsRoot.child(artifact);
                File artifactFile = new File(artifactVirtualFile.toURI());
                logger.println("Uploading artifact '" + artifact + "' to the Telegram chat "
                        + this.chatId);
                try {
                    String response = sendFile(httpClient, httpProxy, descriptor.getBotToken(),
                            expandedCaption, artifactFile);
                    if (response.indexOf("\"ok\":true") < 0) {
                        logger.println("Error while uploading artifact '" + artifact
                                + "' to Telegram chat " + this.chatId + ", response: " + response);
                        return failResult;
                    }
                } catch (Exception e) {
                    logger.println("Can't upload artifact '" + artifactFile
                            + "' to the Telegram, see error below:");
                    e.printStackTrace(logger);
                    return failResult;
                }
            }
        } catch (IOException ioe) {
            // Ignore httpClient.close() IOException
        }

        return true;
    }

    public String sendFile(HttpClient httpClient, HttpHost httpProxy,
            String token, String fileCaption, File file) throws IOException {
        // Build multipart upload request
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);

        // Add parts to multipart request
        builder.addTextBody("chat_id", this.chatId, ContentType.DEFAULT_BINARY);

        if (this.silent) {
            builder.addTextBody("disable_notification", "true", ContentType.DEFAULT_BINARY);
        }

        if (fileCaption != null && !fileCaption.isEmpty()) {
            builder.addTextBody("caption", fileCaption, ContentType.DEFAULT_BINARY);
        }

        builder.addBinaryBody("document", file, ContentType.DEFAULT_BINARY, file.getName());

        HttpEntity data = builder.build();

        // Build HTTP request and assign multipart upload data
        RequestConfig requestConfig = (httpProxy == null) ? RequestConfig.DEFAULT :
            RequestConfig.custom().setProxy(httpProxy).build();
        HttpUriRequest request = RequestBuilder
                .post(String.format("https://api.telegram.org/bot%s/sendDocument", token))
                .setEntity(data)
                .setConfig(requestConfig)
                .build();

        // Create a custom response handler
        ResponseHandler<String> responseHandler = response -> {
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode >= 200 && statusCode < 300) {
                HttpEntity entity = response.getEntity();
                return entity != null ? EntityUtils.toString(entity) : null;
            }
            String status = Integer.toString(statusCode);
            String reason = response.getStatusLine().getReasonPhrase();
            if (reason != null) {
                status += " (" + reason + ")";
            }
            throw new ClientProtocolException("Unexpected response status: " + status);
        };

        return httpClient.execute(request, responseHandler);
    }

    @Extension
    public static final class TelegramUploaderDescriptor extends BuildStepDescriptor<Publisher> {

        private String botToken;
        private String httpProxyUri;
        private String httpProxyUser;
        private String httpProxyPassword;

        public TelegramUploaderDescriptor() {
            load();
        }

        @SuppressWarnings("rawtypes")
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Upload artifacts to Telegram";
        }

        public FormValidation doCheckChatId(@QueryParameter String value) {
            if (value.isEmpty()) {
                return FormValidation.error("Cannot be empty");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckBotToken(@QueryParameter String value) {
            if (value.isEmpty()) {
                return FormValidation.error("Bot token must not be empty");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckHttpProxyUri(@QueryParameter String value) {
            if (!value.isEmpty()) {
                try {
                    URI.create(value);
                } catch (Exception e) {
                    return FormValidation.error("Invalid HTTP proxy URI: %s", e.getMessage());
                }
            }

            return FormValidation.ok();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            JSONObject config = json.getJSONObject("telegram-uploader");
            this.botToken = config.getString("botToken");
            this.httpProxyUri = config.getString("httpProxyUri");
            this.httpProxyUser = config.getString("httpProxyUser");
            this.httpProxyPassword = config.getString("httpProxyPassword");
            save();
            return true;
        }

        public String getBotToken() {
            return botToken;
        }

        public String getHttpProxyUri() {
            return httpProxyUri;
        }

        public String getHttpProxyUser() {
            return httpProxyUser;
        }

        public String getHttpProxyPassword() {
            return httpProxyPassword;
        }
    }

}
