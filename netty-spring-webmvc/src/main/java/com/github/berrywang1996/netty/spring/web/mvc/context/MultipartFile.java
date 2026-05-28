/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.berrywang1996.netty.spring.web.mvc.context;

import java.io.*;

/**
 * Represents an uploaded file received in a multipart HTTP request.
 *
 * <p>Mirrors the Spring MVC {@code MultipartFile} interface with a simple, concrete
 * implementation backed by a byte array. The file data is fully read into memory
 * when the multipart request is parsed.
 *
 * <p>Usage in controller methods:
 * <pre>{@code
 * @PostMapping("/upload")
 * public ResponseEntity<?> upload(MultipartFile file) {
 *     String name = file.getOriginalFilename();
 *     byte[] data = file.getBytes();
 *     file.transferTo(new File("/uploads/" + name));
 *     return ResponseEntity.ok("Uploaded: " + name);
 * }
 * }</pre>
 *
 * @author berrywang1996
 * @since V1.5.0
 */
public class MultipartFile {

    /** The name of the form field in the multipart request. */
    private final String name;

    /** The original filename from the client's file system. */
    private final String originalFilename;

    /** The MIME content type of the uploaded file. */
    private final String contentType;

    /** The raw file content as bytes. */
    private final byte[] content;

    /**
     * Creates a new {@code MultipartFile} with the given metadata and content.
     *
     * @param name             the form field name
     * @param originalFilename the original filename from the client
     * @param contentType      the MIME type of the file
     * @param content          the raw file bytes
     */
    public MultipartFile(String name, String originalFilename, String contentType, byte[] content) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.content = content != null ? content : new byte[0];
    }

    /**
     * Returns the name of the form field in the multipart request.
     *
     * @return the field name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the original filename from the client's file system.
     *
     * <p>This value is supplied by the browser and should not be trusted blindly —
     * sanitize before using in file system paths to prevent path traversal attacks.
     *
     * @return the original filename, or {@code null} if not provided
     */
    public String getOriginalFilename() {
        return originalFilename;
    }

    /**
     * Returns the MIME content type of the uploaded file.
     *
     * @return the content type (e.g. "image/png", "application/pdf"), or {@code null}
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * Returns whether the uploaded file is empty (zero bytes).
     *
     * @return {@code true} if the file has no content
     */
    public boolean isEmpty() {
        return content.length == 0;
    }

    /**
     * Returns the size of the file content in bytes.
     *
     * @return the file size
     */
    public long getSize() {
        return content.length;
    }

    /**
     * Returns the file content as a byte array.
     *
     * @return the raw file bytes
     */
    public byte[] getBytes() {
        return content;
    }

    /**
     * Returns an {@link InputStream} for reading the file content.
     *
     * @return a new input stream over the file bytes
     */
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    /**
     * Transfers the uploaded file content to the given destination file.
     *
     * <p>If the destination file already exists, it will be overwritten.
     *
     * @param dest the destination file
     * @throws IOException if writing to the file fails
     */
    public void transferTo(File dest) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            fos.write(content);
            fos.flush();
        }
    }

    @Override
    public String toString() {
        return "MultipartFile{" +
                "name='" + name + '\'' +
                ", originalFilename='" + originalFilename + '\'' +
                ", contentType='" + contentType + '\'' +
                ", size=" + content.length +
                '}';
    }

}
