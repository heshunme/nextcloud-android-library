/*
 * Nextcloud Android Library
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: MIT
 */
package com.owncloud.android.lib.resources.files;

class NamespaceDeclaration {
    final String prefix;
    final String uri;

    NamespaceDeclaration(String prefix, String uri) {
        this.prefix = prefix == null ? "" : prefix;
        this.uri = uri;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NamespaceDeclaration)) {
            return false;
        }
        NamespaceDeclaration that = (NamespaceDeclaration) other;
        return prefix.equals(that.prefix) && uri.equals(that.uri);
    }

    @Override
    public int hashCode() {
        int result = prefix.hashCode();
        result = 31 * result + uri.hashCode();
        return result;
    }
}
