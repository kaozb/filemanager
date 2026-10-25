package com.lumen.files;

import org.json.JSONArray;
import org.json.JSONObject;

/** WebDAV 服务器配置。密码以本地 SharedPreferences 保存，仅用于连接。 */
public final class Server {

    public final int id;
    public String name;
    public String url;
    public String user;
    public String pass;

    public Server(int id, String name, String url, String user, String pass) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.user = user;
        this.pass = pass;
    }

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("id", id);
            object.put("name", name);
            object.put("url", url);
            object.put("user", user);
            object.put("pass", pass);
        } catch (Exception ignored) {
        }
        return object;
    }

    public static Server fromJson(JSONObject object) {
        return new Server(
                object.optInt("id"),
                object.optString("name", "WebDAV"),
                object.optString("url", ""),
                object.optString("user", ""),
                object.optString("pass", ""));
    }

    public static JSONArray toArray(Iterable<Server> servers) {
        JSONArray array = new JSONArray();
        for (Server server : servers) {
            array.put(server.toJson());
        }
        return array;
    }
}
