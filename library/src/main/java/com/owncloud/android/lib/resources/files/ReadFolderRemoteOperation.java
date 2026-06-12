/*
 * Nextcloud Android Library
 *
 * SPDX-FileCopyrightText: 2015 ownCloud Inc.
 * SPDX-License-Identifier: MIT
 */
package com.owncloud.android.lib.resources.files;

import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.network.WebdavUtils;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.lib.resources.files.model.RemoteFile;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.jackrabbit.webdav.DavConstants;
import org.apache.jackrabbit.webdav.client.methods.PropFindMethod;

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
            query = new PropFindMethod(client.getFilesDavUri(mRemotePath),
                    WebdavUtils.getAllPropSet(),    // PropFind Properties
                    DavConstants.DEPTH_1);
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

    private void readData(PropFindMethod query, OwnCloudClient client) throws Exception {
        if (remoteFileSink != null) {
            StreamingMultiStatusParser parser = new StreamingMultiStatusParser(
                    batchSize,
                    client.getFilesDavUri().getEncodedPath()
            );
            parser.parse(query.getResponseBodyAsStream(), remoteFileSink);
        } else {
            RemoteFileSink collectingSink = new RemoteFileSink() {
                @Override
                public void onFolder(RemoteFile folder) {
                    mFolderAndFiles.add(folder);
                }

                @Override
                public void onChildrenBatch(List<RemoteFile> children) {
                    mFolderAndFiles.addAll(children);
                }
            };

            mFolderAndFiles = new ArrayList<>();
            StreamingMultiStatusParser parser = new StreamingMultiStatusParser(
                    batchSize,
                    client.getFilesDavUri().getEncodedPath()
            );
            parser.parse(query.getResponseBodyAsStream(), collectingSink);
        }
    }
}
