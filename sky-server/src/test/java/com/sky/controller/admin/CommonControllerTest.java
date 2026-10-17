package com.sky.controller.admin;

import com.sky.result.Result;
import com.sky.utils.AliOssUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommonControllerTest {

    @Test
    void storesLocalImageAndRejectsInvalidFilesWithoutCallingOss(@TempDir Path temporary) throws Exception {
        CommonController controller = new CommonController();
        AliOssUtil oss = mock(AliOssUtil.class);
        Path directory = temporary.resolve("images");
        ReflectionTestUtils.setField(controller, "aliOssUtil", oss);
        ReflectionTestUtils.setField(controller, "uploadMode", "local");
        ReflectionTestUtils.setField(controller, "localDir", directory.toString());
        ReflectionTestUtils.setField(controller, "baseUrl", "http://localhost:8080/uploads/");
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l1sAAAAASUVORK5CYII=");

        Result<String> result = controller.upload(new MockMultipartFile("file", "../../dish.PNG", "image/png", png));
        assertEquals(1, result.getCode());
        assertTrue(result.getData().matches("http://localhost:8080/uploads/[0-9a-f-]{36}\\.png"));
        String objectName = result.getData().substring(result.getData().lastIndexOf('/') + 1);
        assertArrayEquals(png, Files.readAllBytes(directory.resolve(objectName)));

        assertEquals(0, controller.upload(new MockMultipartFile("file", "empty.png", "image/png", new byte[0])).getCode());
        assertEquals(0, controller.upload(new MockMultipartFile("file", "dish.html", "text/html", png)).getCode());
        assertEquals(0, controller.upload(new MockMultipartFile("file", "dish", "image/png", png)).getCode());
        ReflectionTestUtils.setField(controller, "uploadMode", "invalid");
        assertEquals(0, controller.upload(new MockMultipartFile("file", "dish.png", "image/png", png)).getCode());
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
        verifyNoInteractions(oss);

        ReflectionTestUtils.setField(controller, "uploadMode", "oss");
        when(oss.upload(eq(png), anyString())).thenReturn("https://example.com/dish.png");
        assertEquals("https://example.com/dish.png", controller.upload(
                new MockMultipartFile("file", "dish.png", "image/png", png)).getData());
        verify(oss).upload(eq(png), anyString());
    }
}
