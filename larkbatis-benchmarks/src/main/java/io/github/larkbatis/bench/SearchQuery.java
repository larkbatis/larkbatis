package io.github.larkbatis.bench;

/**
 * Parameter of the dynamic-SQL benchmark: one pinned id plus three optional
 * filters. The pinned id is always bound and always selects one row, so the
 * three filter settings differ only in how much dynamic SQL is assembled —
 * without it, the cases returning 100 rows measure row reading instead.
 */
public class SearchQuery {

    private long pinnedId;
    private long minId;

    public long getPinnedId() {
        return pinnedId;
    }

    public void setPinnedId(long pinnedId) {
        this.pinnedId = pinnedId;
    }
    private String name;
    private String email;

    public long getMinId() {
        return minId;
    }

    public void setMinId(long minId) {
        this.minId = minId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
