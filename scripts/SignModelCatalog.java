import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Local release tool. Never package this tool, keystore or passwords in an APK/catalog. */
class SignModelCatalog {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException(
            "Usage: java scripts/SignModelCatalog.java <keystore.properties> <payload.tsv> <new-output.gcmodels>");
        Path propertiesPath = Path.of(args[0]).toAbsolutePath();
        Properties properties = new Properties();
        try (var input = Files.newInputStream(propertiesPath)) { properties.load(input); }
        char[] storePassword = Objects.requireNonNull(properties.getProperty("storePassword")).toCharArray();
        char[] keyPassword = Objects.requireNonNull(properties.getProperty("keyPassword")).toCharArray();
        try {
            Path keyPath = propertiesPath.getParent().resolve(properties.getProperty("storeFile")).normalize();
            KeyStore store = KeyStore.getInstance(keyPath.toFile(), storePassword);
            PrivateKey key = (PrivateKey) store.getKey(properties.getProperty("keyAlias"), keyPassword);
            byte[] payload = Files.readAllBytes(Path.of(args[1]));
            if (payload.length > 45_000) throw new IllegalArgumentException("Catalog payload exceeds size bound");
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(payload);
            String document = "GUIDECAST-CATALOG-1\n" + Base64.getEncoder().encodeToString(payload)
                + "\n" + Base64.getEncoder().encodeToString(signer.sign()) + "\n";
            Files.writeString(Path.of(args[2]), document, StandardOpenOption.CREATE_NEW);
            System.out.println("Signed catalog created; contains metadata/signature only. Validate before distribution.");
        } finally {
            Arrays.fill(storePassword, '\0');
            Arrays.fill(keyPassword, '\0');
            properties.clear();
        }
    }
}
