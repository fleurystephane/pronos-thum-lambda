package com.pronos.s3;


import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class PicturesURLs {
    @JsonProperty("keyorigin")
    private String keyorigin;

    @JsonProperty("newkeyorigin")
    private String newkeyorigin;
    @JsonProperty("keythumb")
    private String keythumb;
    @JsonProperty("keyblurred")
    private String keyblurred;
    @JsonProperty("keyblurredthumb")
    private String keyblurredthumb;

    public PicturesURLs(){}

    public PicturesURLs(String keyorigin, String newkeyorigin,
                        String keythumb, String keyblurred, String keyblurredthumb) {
        this.keyorigin = keyorigin;
        this.newkeyorigin = newkeyorigin;
        this.keythumb = keythumb;
        this.keyblurred = keyblurred;
        this.keyblurredthumb = keyblurredthumb;
    }

    public String getKeyorigin() {
        return keyorigin;
    }

    public void setKeyorigin(String keyorigin) {
        this.keyorigin = keyorigin;
    }

    public String getKeythumb() {
        return keythumb;
    }

    public void setKeythumb(String keythumb) {
        this.keythumb = keythumb;
    }

    public String getKeyblurred() {
        return keyblurred;
    }

    public void setKeyblurred(String keyblurred) {
        this.keyblurred = keyblurred;
    }

    public String getKeyblurredthumb() {
        return keyblurredthumb;
    }

    public void setKeyblurredthumb(String keyblurredthumb) {
        this.keyblurredthumb = keyblurredthumb;
    }

    public String getNewkeyorigin() {
        return newkeyorigin;
    }

    public void setNewkeyorigin(String newkeyorigin) {
        this.newkeyorigin = newkeyorigin;
    }

    @Override
    public String toString() {
        return "PicturesURLs{" +
                "keyorigin='" + keyorigin + '\'' +
                ", newkeyorigin='" + newkeyorigin + '\'' +
                ", keythumb='" + keythumb + '\'' +
                ", keyblurred='" + keyblurred + '\'' +
                ", keyblurredthumb='" + keyblurredthumb + '\'' +
                '}';
    }
}
