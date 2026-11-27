package com.kodholken.passdroid;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SessionTest {
    @Test
    public void automaticLogoutWipesKey() {
        Session session = Session.getInstance();
        byte [] key = { 1, 2, 3, 4 };
        session.setKey(key);
        session.setLoggedIn();

        // As done by the timeout and the screen off logout
        session.setLoggedIn(false);

        assertNull(session.getKey());
        assertArrayEquals(new byte[4], key);
        assertFalse(session.isLoggedOutByUser());
    }

    @Test
    public void logoutWipesKey() {
        Session session = Session.getInstance();
        byte [] key = { 1, 2, 3, 4 };
        session.setKey(key);
        session.setLoggedIn();

        session.logout();

        assertFalse(session.isLoggedIn());
        assertNull(session.getKey());
        assertArrayEquals(new byte[4], key);
        assertTrue(session.isLoggedOutByUser());
    }

    @Test
    public void listenerGetsKeyBeforeItIsWiped() {
        Session session = Session.getInstance();
        byte [] key = { 1, 2, 3, 4 };
        session.setKey(key);
        session.setLoggedIn();

        final byte [][] seen = new byte[1][];
        Session.LogoutListener listener = new Session.LogoutListener() {
            @Override
            public void beforeLogout(byte [] key) {
                seen[0] = key.clone();
            }
        };
        session.addLogoutListener(listener);
        try {
            session.setLoggedIn(false);
        } finally {
            session.removeLogoutListener(listener);
        }

        assertArrayEquals(new byte [] { 1, 2, 3, 4 }, seen[0]);
        assertArrayEquals(new byte[4], key);
    }

    @Test
    public void failingListenerDoesNotStopLogout() {
        Session session = Session.getInstance();
        byte [] key = { 1, 2, 3, 4 };
        session.setKey(key);
        session.setLoggedIn();

        final int [] calls = new int[1];
        Session.LogoutListener failing = new Session.LogoutListener() {
            @Override
            public void beforeLogout(byte [] key) {
                calls[0]++;
                throw new IllegalStateException("test");
            }
        };
        Session.LogoutListener next = new Session.LogoutListener() {
            @Override
            public void beforeLogout(byte [] key) {
                calls[0]++;
            }
        };
        session.addLogoutListener(failing);
        session.addLogoutListener(next);
        try {
            session.setLoggedIn(false);
        } finally {
            session.removeLogoutListener(failing);
            session.removeLogoutListener(next);
        }

        assertEquals(2, calls[0]);
        assertFalse(session.isLoggedIn());
        assertNull(session.getKey());
        assertArrayEquals(new byte[4], key);
    }

    @Test
    public void noListenerCallWithoutKey() {
        Session session = Session.getInstance();
        session.setLoggedIn(false);

        final int [] calls = new int[1];
        Session.LogoutListener listener = new Session.LogoutListener() {
            @Override
            public void beforeLogout(byte [] key) {
                calls[0]++;
            }
        };
        session.addLogoutListener(listener);
        try {
            // Already logged out, e.g. a second timeout
            session.setLoggedIn(false);
        } finally {
            session.removeLogoutListener(listener);
        }

        assertEquals(0, calls[0]);
    }

    @Test
    public void timeoutNotDueAfterLogin() {
        Session session = Session.getInstance();
        session.setLoggedIn(false);
        session.setLoggedIn();

        // E.g. the broadcast of an alarm an earlier process set
        assertFalse(session.isTimeoutDue());
    }

    @Test
    public void timeoutDueAfterLogout() {
        Session session = Session.getInstance();
        session.setLoggedIn();

        // As in the screen off logout, which logs out before the broadcast
        session.setLoggedIn(false);

        assertTrue(session.isTimeoutDue());
    }
}
