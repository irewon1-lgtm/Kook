import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.krstock.v3.data.model.EvidenceBundle;
import java.nio.file.Files;
import java.nio.file.Path;
import oracle.ContextCachePolicy;

/** Test-only invocation of the wrapper policy; inputs are labelled controlled scenarios. */
class ContextRetentionHarness {
    public static void main(String[] args) throws Exception {
        Gson gson = new Gson();
        JsonObject previous = gson.fromJson(Files.readString(Path.of(args[0])), JsonObject.class);
        EvidenceBundle incoming = gson.fromJson(Files.readString(Path.of(args[1])), EvidenceBundle.class);
        JsonObject observation = ContextCachePolicy.refresh(previous, incoming, args[2]);
        JsonObject result = new JsonObject();
        result.add("observation", observation);
        result.add("state", ContextCachePolicy.describe(observation, "COLLECTED"));
        System.out.println(gson.toJson(result));
    }
}
