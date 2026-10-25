package com.kodholken.passdroid;

import static org.junit.Assert.assertArrayEquals;
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
}
