/*
 * Nextcloud Android Library
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: MIT
 */
package com.owncloud.android.lib.resources.files;

import com.owncloud.android.lib.resources.files.model.RemoteFile;

import java.io.IOException;
import java.util.List;

/**
 * Receives remote folder data while a PROPFIND response is being parsed.
 */
public interface RemoteFileSink {
    void onFolder(RemoteFile folder) throws IOException;

    void onChildrenBatch(List<RemoteFile> children) throws IOException;
}
