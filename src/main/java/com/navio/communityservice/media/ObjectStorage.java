package com.navio.communityservice.media;
public interface ObjectStorage {
    void put(String key, byte[] content, String contentType);
    byte[] get(String key);
    void delete(String key);
}
