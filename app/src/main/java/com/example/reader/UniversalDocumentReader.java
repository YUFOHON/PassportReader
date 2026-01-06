package com.example.reader;

import android.content.Context;
import android.nfc.Tag;
import android.util.Log;

import com.example.reader.models.DocumentData;
import com.example.reader.readers.DocumentAuthData;
import com.example.reader.readers.EepDocumentReader;
import com.example.reader.readers.IDocumentReader;
import com.example.reader.readers.PassportDocumentReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Universal document reader manager
 * Supports multiple document types and automatically selects the right reader
 */
public class UniversalDocumentReader {

    private static final String TAG = "@@>> UniversalDocReader";

    public interface DocumentReadCallback {
        void onReadStart(DocumentData.DocumentType expectedType);
        void onReadProgress(String message, int progress);
        void onReadSuccess(DocumentData data);
        void onReadError(String errorMessage, Exception exception);
    }

    private Context context;
    private DocumentReadCallback callback;
    private boolean isReading = false;
    private List<IDocumentReader> readers;

    public UniversalDocumentReader(Context context) {
        this.context = context;
        this.readers = new ArrayList<>();

        // Register all available readers
        registerDefaultReaders();
    }

    private void registerDefaultReaders() {
        // Add passport reader
        readers.add(new PassportDocumentReader());
        readers.add(new EepDocumentReader());

        // Future: Add more readers
        // readers.add(new IdCardReader());
        // readers.add(new DriversLicenseReader());
    }

    /**
     * Register a custom document reader
     */
    public void registerReader(IDocumentReader reader) {
        if (!readers.contains(reader)) {
            readers.add(reader);
            Log.d(TAG, "Registered reader: " + reader.getReaderName());
        }
    }

    /**
     * Unregister a document reader
     */
    public void unregisterReader(IDocumentReader reader) {
        readers.remove(reader);
    }

    public void setCallback(DocumentReadCallback callback) {
        this.callback = callback;
    }

    public boolean isReading() {
        return isReading;
    }

    /**
     * Read any supported document
     * Automatically detects document type
     */
    public void readDocument(Tag tag, DocumentAuthData authData) {
        readDocument(tag, authData, null);
    }

    /**
     * Read specific document type
     */
    public void readDocument(Tag tag, DocumentAuthData authData, DocumentData.DocumentType expectedType) {

        // Validate inputs
        if (tag == null) {
            notifyError("No NFC tag detected", null);
            return;
        }

        if (authData == null || !authData.isValid()) {
            notifyError("Invalid authentication data", null);
            return;
        }

        // Find appropriate reader
        IDocumentReader selectedReader = null;

        if (expectedType != null) {
            // Find reader for specific type
            selectedReader = findReaderForType(expectedType, tag);
        } else {
            // Auto-detect reader
            selectedReader = findReaderForTag(tag);
        }

        if (selectedReader == null) {
            notifyError("No compatible reader found for this document", null);
            return;
        }

        // Start reading
        final IDocumentReader reader = selectedReader;
        isReading = true;

        if (callback != null) {
            DocumentData.DocumentType type = expectedType != null ? expectedType :
                    (reader.getSupportedTypes().length > 0 ? reader.getSupportedTypes()[0] : DocumentData.DocumentType.UNKNOWN);
            callback.onReadStart(type);
        }

        new Thread(() -> {
            try {
                Log.d(TAG, "Starting document read with: " + reader.getReaderName());

                notifyProgress("Connecting to chip...", 10);

                DocumentData result = reader.readDocument(tag, authData);

                Log.d(TAG, "Document read completed: " + result.getSummary());

                isReading = false;

                if (callback != null) {
                    callback.onReadSuccess(result);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error reading document: " + e.getMessage(), e);
                isReading = false;
                notifyError(e.getMessage(), e);
            }
        }).start();
    }

    /**
     * Find reader for specific document type
     */
    private IDocumentReader findReaderForType(DocumentData.DocumentType type, Tag tag) {
        for (IDocumentReader reader : readers) {
            for (DocumentData.DocumentType supportedType : reader.getSupportedTypes()) {
                if (supportedType == type && reader.canRead(tag)) {
                    return reader;
                }
            }
        }
        return null;
    }

    /**
     * Auto-detect appropriate reader for tag
     */
    private IDocumentReader findReaderForTag(Tag tag) {
        for (IDocumentReader reader : readers) {
            if (reader.canRead(tag)) {
                Log.d(TAG, "Auto-selected reader: " + reader.getReaderName());
                return reader;
            }
        }
        return null;
    }

    /**
     * Cancel ongoing read operation
     */
    public void cancelRead() {
        isReading = false;
        // Thread interruption logic can be added here
    }

    /**
     * Get list of supported document types
     */
    public List<DocumentData.DocumentType> getSupportedTypes() {
        List<DocumentData.DocumentType> types = new ArrayList<>();
        for (IDocumentReader reader : readers) {
            for (DocumentData.DocumentType type : reader.getSupportedTypes()) {
                if (!types.contains(type)) {
                    types.add(type);
                }
            }
        }
        return types;
    }

    private void notifyProgress(String message, int progress) {
        if (callback != null) {
            callback.onReadProgress(message, progress);
        }
    }

    private void notifyError(String message, Exception e) {
        if (callback != null) {
            callback.onReadError(message, e);
        }
    }
}