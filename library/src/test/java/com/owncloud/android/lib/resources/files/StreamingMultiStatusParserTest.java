/*
 * Nextcloud Android Library
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: MIT
 */
package com.owncloud.android.lib.resources.files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.owncloud.android.lib.resources.files.model.RemoteFile;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class StreamingMultiStatusParserTest {
    private static final int CHILD_COUNT = 100_000;
    private static final int BATCH_SIZE = 500;
    private static final String DAV_PATH_PREFIX = "/remote.php/dav/files/test";

    @Test
    public void streamsLargeMultistatusInBatches() throws Exception {
        String multistatus = createMultistatus(CHILD_COUNT);
        RecordingSink sink = new RecordingSink();

        new StreamingMultiStatusParser(BATCH_SIZE, DAV_PATH_PREFIX)
            .parse(new ByteArrayInputStream(multistatus.getBytes(StandardCharsets.UTF_8)), sink);

        assertEquals("/", sink.folder.getRemotePath());
        assertEquals(CHILD_COUNT, sink.childCount);
        assertEquals(CHILD_COUNT / BATCH_SIZE, sink.batchSizes.size());
        assertTrue(sink.batchSizes.stream().allMatch(size -> size <= BATCH_SIZE));
        assertEquals("/file-000000.txt", sink.firstChildPath);
        assertEquals("/file-099999.txt", sink.lastChildPath);
    }

    @Test
    public void keepsFinalPartialBatch() throws Exception {
        String multistatus = createMultistatus(BATCH_SIZE + 1);
        RecordingSink sink = new RecordingSink();

        new StreamingMultiStatusParser(BATCH_SIZE, DAV_PATH_PREFIX)
            .parse(new ByteArrayInputStream(multistatus.getBytes(StandardCharsets.UTF_8)), sink);

        assertEquals(2, sink.batchSizes.size());
        assertEquals(BATCH_SIZE, sink.batchSizes.get(0).intValue());
        assertEquals(1, sink.batchSizes.get(1).intValue());
    }

    private String createMultistatus(int childCount) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            .append("<d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\" ")
            .append("xmlns:nc=\"http://nextcloud.org/ns\">")
            .append(response("", "root", true, 1));

        for (int i = 0; i < childCount; i++) {
            String name = String.format("file-%06d.txt", i);
            xml.append(response(name, name, false, i + 2L));
        }

        xml.append("</d:multistatus>");
        return xml.toString();
    }

    private String response(String path, String name, boolean folder, long fileId) {
        return "<d:response>"
            + "<d:href>" + DAV_PATH_PREFIX + "/" + path + "</d:href>"
            + "<d:propstat>"
            + "<d:prop>"
            + "<d:displayname>" + name + "</d:displayname>"
            + resourceType(folder)
            + "<d:getcontentlength>10</d:getcontentlength>"
            + "<d:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</d:getlastmodified>"
            + "<d:getetag>\"etag-" + fileId + "\"</d:getetag>"
            + "<oc:fileid>" + fileId + "</oc:fileid>"
            + "<oc:id>remote-" + fileId + "</oc:id>"
            + "<oc:size>10</oc:size>"
            + "<oc:permissions>RGDNVCK</oc:permissions>"
            + "<oc:favorite>0</oc:favorite>"
            + "<nc:is-encrypted>0</nc:is-encrypted>"
            + "<nc:has-preview>false</nc:has-preview>"
            + "</d:prop>"
            + "<d:status>HTTP/1.1 200 OK</d:status>"
            + "</d:propstat>"
            + "</d:response>";
    }

    private String resourceType(boolean folder) {
        if (folder) {
            return "<d:resourcetype><d:collection/></d:resourcetype>";
        }
        return "";
    }

    private static class RecordingSink implements RemoteFileSink {
        private final List<Integer> batchSizes = new ArrayList<>();
        private RemoteFile folder;
        private int childCount;
        private String firstChildPath;
        private String lastChildPath;

        @Override
        public void onFolder(RemoteFile folder) {
            this.folder = folder;
        }

        @Override
        public void onChildrenBatch(List<RemoteFile> children) {
            batchSizes.add(children.size());
            childCount += children.size();
            if (firstChildPath == null) {
                firstChildPath = children.get(0).getRemotePath();
            }
            lastChildPath = children.get(children.size() - 1).getRemotePath();
        }
    }
}
