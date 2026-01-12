package com.example.reader.readers;

import android.nfc.Tag;
import com.example.reader.models.DocumentData;

/**
 * Interface for all document readers
 */
public interface IDocumentReader {

    /**
     * Read document from NFC tag
     */
    DocumentData readDocument(Tag tag, DocumentAuthData authData) throws Exception;

    // Add progress callback support
    DocumentData readDocument(Tag tag, DocumentAuthData authData, ProgressCallback progressCallback) throws Exception;


    /**
     * Check if this reader can handle the given tag
     */
    boolean canRead(Tag tag);

    /**
     * Get supported document types
     */
    DocumentData.DocumentType[] getSupportedTypes();

    /**
     * Get reader name/identifier
     */
    String getReaderName();

    // Progress callback interface
    interface ProgressCallback {
        void onProgress(String message, int progress);
    }

}