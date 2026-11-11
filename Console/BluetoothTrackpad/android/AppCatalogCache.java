package local.remotevox.trackpadtest;

import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A complete catalogue only. Numeric app IDs are revalidated with the Mac before use. */
final class AppCatalogCache {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final AtomicFile file;

    AppCatalogCache(File directory) {
        file = new AtomicFile(new File(directory, "mac-app-catalogue-v1.json"));
    }

    JSONObject read() {
        try (FileInputStream input = file.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) return null;
                bytes.write(buffer, 0, count);
            }
            JSONObject saved = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (saved.optInt("version") != 1 || !saved.optString("catalog").matches("[0-9a-f]{32}")) return null;
            UUID.fromString(saved.getString("host"));
            JSONArray apps = saved.getJSONArray("apps");
            if (apps.length() > Short.MAX_VALUE) return null;
            Set<Integer> ids = new HashSet<>();
            for (int index = 0; index < apps.length(); index++) {
                JSONObject app = apps.getJSONObject(index);
                int id = app.optInt("id");
                if (id <= 0 || id > Short.MAX_VALUE || !ids.add(id) || app.optString("name").isEmpty()
                        || !app.optString("key").matches("[0-9a-f]{24}")) return null;
            }
            return saved;
        } catch (IOException | JSONException | IllegalArgumentException invalid) {
            return null;
        }
    }

    void save(String host, String revision, List<JSONObject> apps) {
        FileOutputStream output = null;
        try {
            JSONObject saved = new JSONObject().put("version", 1).put("host", host)
                    .put("catalog", revision).put("apps", new JSONArray(apps));
            byte[] bytes = saved.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) return;
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException | JSONException error) {
            if (output != null) file.failWrite(output);
        }
    }
}
