package com.proxeus.xml.template;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Map;

public interface TemplateRenderer {
    void render(InputStream input, OutputStream output, Map<String, Object> data, Charset charset) throws Exception;
}
