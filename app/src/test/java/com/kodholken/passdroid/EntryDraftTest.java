package com.kodholken.passdroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EntryDraftTest {
    private static final byte [] KEY = key(1);
    private static final byte [] OTHER_KEY = key(2);

    private static byte [] key(int seed) {
        byte [] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (seed * 31 + i);
        }
        return key;
    }

    private static PasswordEntry values() {
        PasswordEntry values = new PasswordEntry();
        values.setDecSystem("GitHub");
        values.setDecUsername("alice");
        values.setDecPassword("s3cret|with|bars");
        values.setDecNote("");
        values.setDecUrl("https://github.com");
        values.setDecEmail("alice@example.com");
        values.setDecTotp("JBSWY3DPEHPK3PXP");
        values.setDecDate(null);
        return values;
    }

    @Test
    public void encodeAndDecode() {
        PasswordEntry encrypted = new PasswordEntry();
        encrypted.setEncSystem("c3lzdGVt");
        encrypted.setEncUsername("");
        encrypted.setEncPassword("cGFzcw==");
        encrypted.setEncNote(null);
        encrypted.setEncUrl("dXJs");
        encrypted.setEncEmail(null);
        encrypted.setEncTotp("dG90cA==");
        encrypted.setEncDate("ZGF0ZQ==");

        for (long entryId : new long [] { -1, 42 }) {
            String value = EntryDraft.encode(encrypted, entryId, 1234567890123L);
            EntryDraft.Draft draft = EntryDraft.decode(value);

            assertNotNull(draft);
            assertEquals(entryId, draft.entryId);
            assertEquals(1234567890123L, draft.savedAt);
            assertEquals("c3lzdGVt", draft.values.getEncSystem());
            // An empty value is stored like a missing one
            assertNull(draft.values.getEncUsername());
            assertEquals("cGFzcw==", draft.values.getEncPassword());
            assertNull(draft.values.getEncNote());
            assertEquals("dXJs", draft.values.getEncUrl());
            assertNull(draft.values.getEncEmail());
            assertEquals("dG90cA==", draft.values.getEncTotp());
            assertEquals("ZGF0ZQ==", draft.values.getEncDate());
        }
    }

    @Test
    public void decodeRejectsOtherText() {
        assertNull(EntryDraft.decode(""));
        assertNull(EntryDraft.decode("v1|-1|0|a|b"));
        assertNull(EntryDraft.decode("v2|-1|0|||||||||"));
        assertNull(EntryDraft.decode("v1|x|0|||||||||"));
        assertNull(EntryDraft.decode("v1|-1|0||||||||||"));
        assertNotNull(EntryDraft.decode("v1|-1|0||||||||"));
    }

    @Test
    public void encryptAndDecrypt() {
        PasswordEntry values = values();
        String value = EntryDraft.encrypt(KEY, values, 7, 1000);

        assertNotNull(value);
        assertTrue(value.startsWith("v1|7|1000|"));
        // Nothing is stored in the clear
        assertTrue(!value.contains("GitHub") && !value.contains("s3cret"));

        EntryDraft.Draft draft = EntryDraft.decrypt(value, KEY);
        assertNotNull(draft);
        assertEquals(7, draft.entryId);
        assertEquals(1000, draft.savedAt);
        assertEquals("GitHub", draft.values.getDecSystem());
        assertEquals("alice", draft.values.getDecUsername());
        assertEquals("s3cret|with|bars", draft.values.getDecPassword());
        assertEquals("", draft.values.getDecNote());
        assertEquals("https://github.com", draft.values.getDecUrl());
        assertEquals("alice@example.com", draft.values.getDecEmail());
        assertEquals("JBSWY3DPEHPK3PXP", draft.values.getDecTotp());
        assertNull(draft.values.getDecDate());

        // The values given are not changed
        assertNull(values.getEncPassword());
    }

    @Test
    public void otherKeyDoesNotDecrypt() {
        String value = EntryDraft.encrypt(KEY, values(), -1, 1000);

        assertNull(EntryDraft.decrypt(value, OTHER_KEY));
        assertNull(EntryDraft.decrypt(value, null));
    }

    @Test
    public void noKeyNoDraft() {
        assertNull(EntryDraft.encrypt(null, values(), -1, 1000));
    }

    @Test
    public void differ() {
        assertFalse(EntryDraft.differ(values(), values()));

        PasswordEntry changed = values();
        changed.setDecNote("PIN 1234");
        assertTrue(EntryDraft.differ(values(), changed));

        changed = values();
        changed.setDecDate("2026-11-24T10:00");
        assertTrue(EntryDraft.differ(values(), changed));

        changed = values();
        changed.setDecPassword("other");
        assertTrue(EntryDraft.differ(values(), changed));
    }
}
