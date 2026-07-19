package com.easyshop.user.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shipping_addresses")
public class ShippingAddress {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;


    @NotBlank
    @Column(nullable = false, length = 50)
    private String label;

    @NotBlank
    @Column(nullable = false)
    private String line1;

    private String line2;

    @NotBlank
    @Column(nullable = false, length = 100)
    private String city;

    @Column(name = "state_province", length = 100)
    private String stateProvince;

    @NotBlank
    @Column(name = "postal_code", nullable = false, length = 20)
    private String postalCode;

    @NotBlank
    @Column(name = "country_code", nullable = false, length = 20)
    private String countryCode;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShippingAddress() {}

    public static ShippingAddress createNew(User user, String label, String line1, String line2,
                                            String city, String stateProvince, String postalCode,
                                            String countryCode) {
        ShippingAddress address = new ShippingAddress();
        address.user = user;
        address.label = label;
        address.line1 = line1;
        address.line2 = line2;
        address.city = city;
        address.stateProvince = stateProvince;
        address.postalCode = postalCode;
        address.countryCode = countryCode;
        return address;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public void markAsDefault() { this.isDefault = true; }
    public void unmarkAsDefault() { this.isDefault = false; }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getLabel() {
        return label;
    }

    public String getLine1() {
        return line1;
    }

    public String getLine2() {
        return line2;
    }

    public String getCity() {
        return city;
    }

    public String getStateProvince() {
        return stateProvince;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}