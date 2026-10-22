package com.yugabyte.metadata.catalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.yugabyte.metadata.web.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Pages {
  private Pages() {}

  public record Window(int pageSize, int offset) {}

  public static Window window(String pageSize, String pageToken, String page, int fallback, ObjectMapper mapper) {
    int size = fallback;
    if (pageSize != null && !pageSize.isBlank()) {
      try {
        size = Integer.parseInt(pageSize);
      } catch (NumberFormatException ignored) {
        size = fallback;
      }
    }
    size = Math.min(Math.max(size, 1), 200);

    int offset = 0;
    if (pageToken != null && !pageToken.isBlank()) {
      try {
        byte[] decoded = Base64.getUrlDecoder().decode(pageToken);
        JsonNode node = mapper.readTree(new String(decoded, StandardCharsets.UTF_8));
        offset = Math.max(node.path("offset").asInt(0), 0);
      } catch (Exception exception) {
        throw new BadRequestException("pageToken is not valid");
      }
    } else if (page != null && !page.isBlank()) {
      int pageNumber;
      try {
        pageNumber = Integer.parseInt(page);
      } catch (NumberFormatException exception) {
        pageNumber = 1;
      }
      offset = (Math.max(pageNumber, 1) - 1) * size;
    }
    return new Window(size, offset);
  }

  public static Map<String, Object> body(List<?> items, int pageSize, int offset, int total) {
    boolean hasMore = offset + items.size() < total;
    String nextPageToken = null;
    if (hasMore) {
      String json = "{\"offset\":" + (offset + pageSize) + "}";
      nextPageToken = Base64.getUrlEncoder().withoutPadding()
          .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("pageSize", pageSize);
    page.put("nextPageToken", nextPageToken);
    page.put("total", total);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", items);
    body.put("page", page);
    return body;
  }
}
