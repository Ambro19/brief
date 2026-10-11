package unit;

import Util.PasswordHasher;
import org.junit.Test;

import static org.junit.Assert.*;

public class PasswordHasherTest {

    @Test
    public void hashIsNotThePasswordAndVerifies() {
        String stored = PasswordHasher.hash("correct horse");
        assertNotEquals("correct horse", stored);
        assertFalse(stored.contains("correct horse"));
        assertTrue(PasswordHasher.isHashed(stored));
        assertTrue(PasswordHasher.verify("correct horse", stored));
    }

    @Test
    public void wrongPasswordDoesNotVerify() {
        String stored = PasswordHasher.hash("correct horse");
        assertFalse(PasswordHasher.verify("wrong horse", stored));
        assertFalse(PasswordHasher.verify(null, stored));
    }

    @Test
    public void samePasswordHashesDifferentlyEachTime() {
        // A fresh random salt per hash means identical passwords are not visible in the database.
        assertNotEquals(PasswordHasher.hash("password"), PasswordHasher.hash("password"));
    }

    @Test
    public void plainTextIsNotMistakenForAHash() {
        assertFalse(PasswordHasher.isHashed("password"));
        assertFalse(PasswordHasher.isHashed("pbkdf2$notanumber$AAAA$BBBB"));
        assertFalse(PasswordHasher.isHashed(null));
        assertFalse(PasswordHasher.verify("password", "password"));
    }

    @Test
    public void storedValueFitsTheColumn() {
        assertTrue(PasswordHasher.hash("a-reasonably-long-password-1234567890").length() <= 255);
    }
}
