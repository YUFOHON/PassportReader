package com.example.reader.models;

public class PassportDocumentData extends DocumentData {

    public PassportDocumentData() {
        super(DocumentType.PASSPORT); // or DocumentType.EEEP if you prefer
    }

    @Override
    public boolean isValid() {
        return documentNumber != null && !documentNumber.isEmpty()
                && dateOfBirth != null && !dateOfBirth.isEmpty()
                && dateOfExpiry != null && !dateOfExpiry.isEmpty();
    }

    @Override
    public String getSummary() {
        String name = (firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName);
        name = name.trim();
        return "Passport " + (documentNumber == null ? "" : documentNumber)
                + (name.isEmpty() ? "" : " • " + name)
                + (nationality == null ? "" : " • " + nationality);
    }
}