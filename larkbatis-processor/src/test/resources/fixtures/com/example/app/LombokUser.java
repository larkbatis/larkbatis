package com.example.app;

/** Fields but no setters, as a result class looks before Lombok has run. */
@lombok.Setter
public class LombokUser {

    private long id;
    private String name;
}
