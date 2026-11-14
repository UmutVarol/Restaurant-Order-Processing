package com.umutvarol.restaurant.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/** A staff account that can sign in to the point-of-sale. */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The business this belongs to; Hibernate filters every query by it (docs/adr/0001-multi-business.md). */
    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "pin_hash", length = 100)
    private String pinHash;

    @Column(name = "pin_lockouts", nullable = false)
    private int pinLockouts;

    @Column(name = "totp_secret", length = 255)
    private String totpSecret;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled;

    @Column(name = "totp_last_step", nullable = false)
    private long totpLastStep;

    /** Tokens carrying an older version no longer work (see SessionGuard). */
    @Column(name = "session_version", nullable = false)
    private int sessionVersion;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "created_at")
    private Instant createdAt;

    protected AppUser() {
        // for JPA
    }

    public AppUser(String username, String passwordHash, String displayName, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
    }

    /** An account whose password someone else chose: it has to be replaced at the first sign-in. */
    public static AppUser withTemporaryPassword(String username, String passwordHash, String displayName, Role role,
                                         Instant now) {
        AppUser user = new AppUser(username, passwordHash, displayName, role);
        user.mustChangePassword = true;
        user.createdAt = now;
        return user;
    }

    /** The owner chose a new password: every other session ends. */
    void changePassword(String newHash, Instant now) {
        this.passwordHash = newHash;
        this.mustChangePassword = false;
        endSessions(now);
    }

    /** A manager set a temporary password: the owner must replace it, and every session ends. */
    void resetPassword(String temporaryHash, Instant now) {
        this.passwordHash = temporaryHash;
        this.mustChangePassword = true;
        endSessions(now);
    }

    /** @return whether anything that a signed-in token carries (role, being allowed in) changed */
    boolean update(String displayName, Role role, boolean enabled, Instant now) {
        boolean access = this.role != role || this.enabled != enabled;
        this.displayName = displayName;
        this.role = role;
        this.enabled = enabled;
        if (access) {
            endSessions(now);
            if (!enabled) {
                this.pinHash = null;
            }
        }
        return access;
    }

    void clearPin() {
        this.pinHash = null;
        this.pinLockouts = 0;
    }

    private void endSessions(Instant now) {
        this.sessionVersion++;
    }

    public int getSessionVersion() {
        return sessionVersion;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public @Nullable Instant getCreatedAt() {
        return createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getBusinessId() {
        return businessId;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean hasPin() {
        return pinHash != null;
    }

    String getPinHash() {
        return pinHash;
    }

    void setPinHash(String pinHash) {
        this.pinHash = pinHash;
        this.pinLockouts = 0;
    }

    /** Counts a PIN lockout; after {@code limit} in a row the PIN is cleared. @return whether it was cleared */
    boolean pinLockedOut(int limit) {
        pinLockouts++;
        if (pinLockouts >= limit) {
            pinHash = null;
            pinLockouts = 0;
            return true;
        }
        return false;
    }

    void pinAccepted() {
        pinLockouts = 0;
    }

    public boolean isTotpEnabled() {
        return totpEnabled;
    }

    String getTotpSecret() {
        return totpSecret;
    }

    long getTotpLastStep() {
        return totpLastStep;
    }

    /** Starts enrolment: the secret is stored, but not required until a first code confirms it. */
    void startTotp(String sealedSecret) {
        this.totpSecret = sealedSecret;
        this.totpEnabled = false;
        this.totpLastStep = 0;
    }

    void enableTotp(long step) {
        this.totpEnabled = true;
        this.totpLastStep = step;
    }

    void totpUsed(long step) {
        this.totpLastStep = step;
    }

    void disableTotp() {
        this.totpSecret = null;
        this.totpEnabled = false;
        this.totpLastStep = 0;
    }
}
