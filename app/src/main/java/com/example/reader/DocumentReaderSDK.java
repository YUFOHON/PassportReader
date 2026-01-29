    package com.example.reader;

    import android.app.Activity;
    import android.content.Intent;
    import android.nfc.Tag;
    import androidx.annotation.NonNull;
    import androidx.fragment.app.FragmentActivity;

    import com.example.reader.models.DocumentData;
    import com.example.reader.readers.DocumentAuthData;
    import com.example.reader.utils.Constants;
    import com.example.reader.utils.NfcHelper;

    import java.lang.ref.WeakReference;

    public class DocumentReaderSDK {

        private static DocumentReaderSDK instance;

        private WeakReference<Activity> activityRef;  // Use WeakReference
        private WeakReference<DocumentReaderCallback> callbackRef;  // Use WeakReference

    //    private Activity activity;
        private UniversalDocumentReader nfcReader;
        private NfcHelper nfcHelper;
    //    private DocumentReaderCallback callback;

        // Stored MRZ data
        private String documentNumber;
        private String dateOfBirth;
        private String dateOfExpiry;
        private DocumentData.DocumentType documentType;

        public static final int REQUEST_CODE_SCAN = 1001;

        public Configuration getConfiguration() {
            return configuration;
        }

        // ==================== CALLBACK INTERFACE ====================

        public interface DocumentReaderCallback {
            void onMrzScanned(MrzResult result);
            void onNfcReadProgress(String message, int progress);
            void onDocumentRead(DocumentData data);
            void onError(ErrorType type, String message);
        }

        public enum ErrorType {
            SCAN_CANCELLED,
            NFC_NOT_AVAILABLE,
            NFC_DISABLED,
            NFC_READ_FAILED,
            INVALID_MRZ
        }

        public static class MrzResult {
            public String documentNumber;
            public String dateOfBirth;
            public String dateOfExpiry;
            public DocumentData.DocumentType documentType;
            public int mrzLines;
        }

        // ==================== INITIALIZATION ====================
        private Configuration configuration = new Configuration();

        private DocumentReaderSDK() {}

        public static DocumentReaderSDK getInstance() {
            if (instance == null) {
                instance = new DocumentReaderSDK();
            }
            return instance;
        }

        public void init(@NonNull Activity activity) {
            init(activity, null);
        }

        public void init(@NonNull Activity activity, Configuration config) {
    //        this.activity = activity;
            this.activityRef = new WeakReference<>(activity);
            this.configuration = config != null ? config : new Configuration();
            this.nfcReader = new UniversalDocumentReader(activity);
            this.nfcHelper = new NfcHelper(activity);
            setupNfcReaderCallback();
        }

    //    public void setCallback(DocumentReaderCallback callback) {
    //        this.callback = callback;
    //    }
        public void setCallback(DocumentReaderCallback callback) {
        this.callbackRef = new WeakReference<>(callback);
    }

        private DocumentReaderCallback getCallback() {
            return callbackRef != null ? callbackRef.get() : null;
        }
        // ==================== SCAN MRZ ====================

        public void scanDocument() {
            Activity activity = getActivity();
            if (activity != null) {
                Intent intent = new Intent(activity, CameraActivity.class);
                activity.startActivityForResult(intent, REQUEST_CODE_SCAN);
            }
    //        Intent intent = new Intent(activity, CameraActivity.class);
    //        activity.startActivityForResult(intent, REQUEST_CODE_SCAN);
        }

        // Call this from your Activity's onActivityResult
        public void handleActivityResult(int requestCode, int resultCode, Intent data) {
            DocumentReaderCallback callback = getCallback();  // Add this

            if (requestCode != REQUEST_CODE_SCAN || callback == null) return;

            if (resultCode == Activity.RESULT_OK && data != null) {
                documentNumber = data.getStringExtra(Constants.EXTRA_DOC_NUM);
                dateOfBirth = data.getStringExtra(Constants.EXTRA_DOB);
                dateOfExpiry = data.getStringExtra(Constants.EXTRA_EXPIRY);

                String typeStr = data.getStringExtra(Constants.EXTRA_DOC_TYPE);
                if (typeStr != null) {
                    documentType = DocumentData.DocumentType.valueOf(typeStr);
                }

                MrzResult result = new MrzResult();
                result.documentNumber = documentNumber;
                result.dateOfBirth = dateOfBirth;
                result.dateOfExpiry = dateOfExpiry;
                result.documentType = documentType;
                result.mrzLines = data.getIntExtra(Constants.EXTRA_MRZ_LINES, 0);

                callback.onMrzScanned(result);
            } else {
                callback.onError(ErrorType.SCAN_CANCELLED, "Scan cancelled");
            }
        }

        // ==================== NFC READING ====================

        public void startNfcReading() {
            DocumentReaderCallback callback = getCallback();  // Add this

            if (!nfcHelper.isNfcAvailable()) {
                if (callback != null) callback.onError(ErrorType.NFC_NOT_AVAILABLE, "NFC not available");
                return;
            }

            if (!nfcHelper.isNfcEnabled()) {
                if (callback != null) callback.onError(ErrorType.NFC_DISABLED, "Please enable NFC");
                return;
            }

            if (documentNumber == null || dateOfBirth == null || dateOfExpiry == null) {
                if (callback != null) callback.onError(ErrorType.INVALID_MRZ, "Scan document first");
                return;
            }

            nfcHelper.enableForegroundDispatch();
        }

        public void stopNfcReading() {
            nfcHelper.disableForegroundDispatch();
        }

        // Call this from your Activity's onNewIntent
        public void handleNfcIntent(Intent intent) {
            Tag tag = nfcHelper.getTagFromIntent(intent);
            if (tag != null && documentNumber != null) {
                DocumentAuthData authData = new DocumentAuthData(documentNumber, dateOfBirth, dateOfExpiry);
                nfcReader.readDocument(tag, authData, documentType);
            }
        }

        // ==================== LIFECYCLE ====================

        public void onResume() {
            // Re-enable NFC if it was active
        }

        public void onPause() {
            stopNfcReading();
        }

        public void onDestroy() {
            nfcReader.cancelRead();
            stopNfcReading();
        }

        public void release() {
            if (nfcReader != null) {
                nfcReader.cancelRead();
            }
            if (nfcHelper != null) {
                nfcHelper.disableForegroundDispatch();
            }
            activityRef = null;
            callbackRef = null;
            nfcReader = null;
            nfcHelper = null;
        }
        // ==================== INTERNAL ====================
        private Activity getActivity() {
            Activity activity = activityRef != null ? activityRef.get() : null;
            return (activity != null && !activity.isFinishing()) ? activity : null;
        }
        private void setupNfcReaderCallback() {
            nfcReader.setCallback(new UniversalDocumentReader.DocumentReadCallback() {
                @Override
                public void onReadStart(DocumentData.DocumentType type) {
                    DocumentReaderCallback cb = getCallback();
                    if (cb != null) cb.onNfcReadProgress("Starting...", 0);
                }

                @Override
                public void onReadProgress(String message, int progress) {
                    DocumentReaderCallback cb = getCallback();
                    if (cb != null) cb.onNfcReadProgress(message, progress);
                }

                @Override
                public void onReadSuccess(DocumentData data) {
                    stopNfcReading();
                    DocumentReaderCallback cb = getCallback();
                    if (cb != null) cb.onDocumentRead(data);
                }

                @Override
                public void onReadError(String message, Exception e) {
                    stopNfcReading();
                    DocumentReaderCallback cb = getCallback();
                    if (cb != null) cb.onError(ErrorType.NFC_READ_FAILED, message);
                }
            });
        }
    }