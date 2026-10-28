package com.umutvarol.restaurant.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A staff account that can sign in to the point-of-sale. */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    protected AppUser() {
        // for JPA
    }

    public AppUser(String username, String passwordHash, String displayName, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
    }

    public Long getId() {
        return id;
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
