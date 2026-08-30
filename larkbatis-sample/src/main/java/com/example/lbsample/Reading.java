package com.example.lbsample;

import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Date;

/**
 * A row of the shapes a codebase of a certain age is actually made of.
 *
 * <p>Every property here was a compile error until the type whitelist was
 * widened, and each one on its own was enough to block the first statement
 * that returned the class. Keeping them in one bean is the point: a migration
 * meets them together, in a DTO nobody wants to rewrite before the mappers
 * even compile.
 *
 * <p>{@code java.sql.Date} is deliberately absent — it is
 * {@code java.util.Date}'s subclass, and the pair being one import apart is
 * how the wrong one gets picked. It is covered by
 * {@code TypeWhitelistEndToEndTest} through {@code dueOn} instead.
 */
public class Reading {

    private long id;
    /** Read back as a Timestamp and narrowed, as MyBatis's DateTypeHandler does. */
    private Date takenAt;
    private Timestamp recordedAt;
    private java.sql.Date dueOn;
    private Time alarmAt;
    private OffsetDateTime observedAt;
    private BigInteger counter;
    private char grade;
    private Character flag;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Date getTakenAt() {
        return takenAt;
    }

    public void setTakenAt(Date takenAt) {
        this.takenAt = takenAt;
    }

    public Timestamp getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Timestamp recordedAt) {
        this.recordedAt = recordedAt;
    }

    public java.sql.Date getDueOn() {
        return dueOn;
    }

    public void setDueOn(java.sql.Date dueOn) {
        this.dueOn = dueOn;
    }

    public Time getAlarmAt() {
        return alarmAt;
    }

    public void setAlarmAt(Time alarmAt) {
        this.alarmAt = alarmAt;
    }

    public OffsetDateTime getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(OffsetDateTime observedAt) {
        this.observedAt = observedAt;
    }

    public BigInteger getCounter() {
        return counter;
    }

    public void setCounter(BigInteger counter) {
        this.counter = counter;
    }

    public char getGrade() {
        return grade;
    }

    public void setGrade(char grade) {
        this.grade = grade;
    }

    public Character getFlag() {
        return flag;
    }

    public void setFlag(Character flag) {
        this.flag = flag;
    }
}
