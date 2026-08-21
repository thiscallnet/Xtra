package com.github.andreyasadchy.xtra.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

class DownloadStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Resolves SAF children through the provider instead of guessing encoded URI paths. */
fun ContentResolver.findChildDocument(parent: Uri, displayName: String, mimeType: String): Uri? {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        parent,
        DocumentsContract.getDocumentId(parent),
    )
    query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (idIndex >= 0 && nameIndex >= 0 && mimeIndex >= 0 && cursor.moveToNext()) {
            val actualMimeType = cursor.getString(mimeIndex)
            val typeMatches = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                actualMimeType == DocumentsContract.Document.MIME_TYPE_DIR
            } else {
                actualMimeType != null && actualMimeType != DocumentsContract.Document.MIME_TYPE_DIR
            }
            if (cursor.getString(nameIndex) == displayName && typeMatches) {
                return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(idIndex))
            }
        }
    }
    return null
}

fun ContentResolver.createOrFindDocument(parent: Uri, mimeType: String, displayName: String): Uri =
    try {
        findChildDocument(parent, displayName, mimeType)
            ?: DocumentsContract.createDocument(this, parent, mimeType, displayName)
            ?: throw DownloadStorageException("Unable to create document: $displayName")
    } catch (e: DownloadStorageException) {
        throw e
    } catch (e: Exception) {
        throw DownloadStorageException("Unable to create document: $displayName", e)
    }

fun ContentResolver.createOrFindDirectory(parent: Uri, displayName: String): Uri =
    createOrFindDocument(parent, DocumentsContract.Document.MIME_TYPE_DIR, displayName)
