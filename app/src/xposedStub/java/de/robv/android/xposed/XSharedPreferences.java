package de.robv.android.xposed;

import java.io.File;
import java.util.Map;
import java.util.Set;

public final class XSharedPreferences {
    public XSharedPreferences(File prefFile) {}
    public XSharedPreferences(String packageName) {}
    public XSharedPreferences(String packageName, String prefFileName) {}
    public boolean makeWorldReadable() { return true; }
    public void reload() {}
    public boolean getBoolean(String key, boolean defValue) { return defValue; }
    public String getString(String key, String defValue) { return defValue; }
    public int getInt(String key, int defValue) { return defValue; }
    public long getLong(String key, long defValue) { return defValue; }
    public float getFloat(String key, float defValue) { return defValue; }
    public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
    public Map<String, ?> getAll() { return null; }
}
