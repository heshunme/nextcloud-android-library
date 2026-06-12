/*
 * Nextcloud Android Library
 *
 * SPDX-FileCopyrightText: 2015 ownCloud Inc.
 * SPDX-License-Identifier: MIT
 */
package com.owncloud.android.lib.resources.files;

import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.network.WebdavEntry;
import com.owncloud.android.lib.common.network.WebdavUtils;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.lib.resources.files.model.RemoteFile;

import org.apache.commons.httpclient.HttpConnection;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.HttpState;
import org.apache.jackrabbit.webdav.DavConstants;
import org.apache.jackrabbit.webdav.MultiStatus;
import org.apache.jackrabbit.webdav.MultiStatusResponse;
import org.apache.jackrabbit.webdav.client.methods.PropFindMethod;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Remote operation performing the read of remote file or folder in the ownCloud server.
 *
 * @author David A. Velasco
 * @author masensio
 */

public class ReadFolderRemoteOperation extends RemoteOperation {

    private static final String TAG = ReadFolderRemoteOperation.class.getSimpleName();

    private String mRemotePath;
    private ArrayList<Object> mFolderAndFiles;
    private final RemoteFileSink remoteFileSink;
    private final int batchSize;

    /**
     * Constructor
     *
     * @param remotePath Remote path of the file.
     */
    public ReadFolderRemoteOperation(String remotePath) {
        mRemotePath = remotePath;
        remoteFileSink = null;
        batchSize = StreamingMultiStatusParser.DEFAULT_BATCH_SIZE;
    }

    public ReadFolderRemoteOperation(String remotePath, RemoteFileSink remoteFileSink) {
        this(remotePath, remoteFileSink, StreamingMultiStatusParser.DEFAULT_BATCH_SIZE);
    }

    public ReadFolderRemoteOperation(String remotePath, RemoteFileSink remoteFileSink, int batchSize) {
        mRemotePath = remotePath;
        this.remoteFileSink = remoteFileSink;
        this.batchSize = batchSize;
    }

    /**
     * Performs the read operation.
     *
     * @param client Client object to communicate with the remote ownCloud server.
     */
    @Override
    protected RemoteOperationResult run(OwnCloudClient client) {
        RemoteOperationResult result = null;
        PropFindMethod query = null;

        try {
            // remote request
            query = createPropFindMethod(client);
            int status = client.executeMethod(query);

            // check and process response
            boolean isSuccess = (status == HttpStatus.SC_MULTI_STATUS || status == HttpStatus.SC_OK);
            
            if (isSuccess) {
                // get data from remote folder
                readData(query, client);

                // Result of the operation
                result = new RemoteOperationResult(true, query);
                // Add data to the result
                if (result.isSuccess() && mFolderAndFiles != null) {
                    result.setData(mFolderAndFiles);
                }
            } else {
                // synchronization failed
                client.exhaustResponse(query.getResponseBodyAsStream());
                result = new RemoteOperationResult(false, query);
            }
        } catch (Exception e) {
            result = new RemoteOperationResult(e);
        } finally {
            if (query != null)
                query.releaseConnection();  // let the connection available for other methods

            if (result == null) {
                result = new RemoteOperationResult(new Exception("unknown error"));
                Log_OC.e(TAG, "Synchronized " + mRemotePath + ": failed");
            } else {
                if (result.isSuccess()) {
                    Log_OC.i(TAG, "Synchronized " + mRemotePath + ": " + result.getLogMessage());
                } else {
                    if (result.isException()) {
                        Log_OC.e(TAG, "Synchronized " + mRemotePath + ": " + result.getLogMessage(),
                                result.getException());
                    } else {
                        Log_OC.e(TAG, "Synchronized " + mRemotePath + ": " + result.getLogMessage());
                    }
                }
            }
        }
        
        return result;
    }

    public boolean isMultiStatus(int status) {
        return (status == HttpStatus.SC_MULTI_STATUS);
    }

    private PropFindMethod createPropFindMethod(OwnCloudClient client) throws IOException {
        if (remoteFileSink != null) {
            return new StreamingPropFindMethod(client.getFilesDavUri(mRemotePath));
        }

        return new PropFindMethod(client.getFilesDavUri(mRemotePath),
                WebdavUtils.getAllPropSet(),    // PropFind Properties
                DavConstants.DEPTH_1);
    }

    private void readData(PropFindMethod query, OwnCloudClient client) throws Exception {
        RemoteFileSink sink = remoteFileSink;
        if (sink == null) {
            mFolderAndFiles = new ArrayList<>();
            sink = new RemoteFileSink() {
                @Override
                public void onFolder(RemoteFile folder) {
                    mFolderAndFiles.add(folder);
                }

                @Override
                public void onChildrenBatch(List<RemoteFile> children) {
                    mFolderAndFiles.addAll(children);
                }
            };
        }

        InputStream responseStream = query.getResponseBodyAsStream();
        if (responseStream != null) {
            StreamingMultiStatusParser parser = new StreamingMultiStatusParser(
                    batchSize,
                    client.getFilesDavUri().getEncodedPath(),
                    mRemotePath
            );
            try {
                parser.parse(responseStream, sink);
                return;
            } catch (IOException e) {
                Log_OC.w(TAG, "Streaming PROPFIND parser failed, falling back to MultiStatus parser: " + e);
            }
        }

        readDataFromMultiStatus(query, client, sink);
    }

    private void readDataFromMultiStatus(PropFindMethod query, OwnCloudClient client, RemoteFileSink sink)
            throws Exception {
        MultiStatus dataInServer = query.getResponseBodyAsMultiStatus();
        MultiStatusResponse[] responses = dataInServer.getResponses();
        if (responses.length == 0) {
            return;
        }

        String splitElement = client.getFilesDavUri().getEncodedPath();
        sink.onFolder(new RemoteFile(new WebdavEntry(responses[0], splitElement)));

        List<RemoteFile> children = new ArrayList<>(batchSize);
        for (int i = 1; i < responses.length; i++) {
            children.add(new RemoteFile(new WebdavEntry(responses[i], splitElement)));
            if (children.size() == batchSize) {
                sink.onChildrenBatch(new ArrayList<>(children));
                children.clear();
            }
        }

        if (!children.isEmpty()) {
            sink.onChildrenBatch(new ArrayList<>(children));
        }
    }

    private static class StreamingPropFindMethod extends PropFindMethod {
        StreamingPropFindMethod(String uri) throws IOException {
            super(uri, WebdavUtils.getAllPropSet(), DavConstants.DEPTH_1);
        }

        @Override
        protected void processResponseBody(HttpState httpState, HttpConnection httpConnection) {
            // Keep the response stream untouched so large PROPFIND bodies can be parsed incrementally.
        }
    }

}
