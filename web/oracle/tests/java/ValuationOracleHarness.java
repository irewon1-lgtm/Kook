import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.krstock.v3.data.model.StockSummary;
import com.krstock.v3.data.model.ValuationBand;
import com.krstock.v3.data.ranking.DynamicRankResult;
import com.krstock.v3.data.ranking.DynamicRankingEngine;
import com.krstock.v3.data.stage.ValuationBandPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independently calls the original classifier and checks its non-mutating rank contract. */
class ValuationOracleHarness {
    static Map<Integer, Map<String, DynamicRankResult>> ranks(List<StockSummary> stocks) {
        Map<Integer, Map<String, DynamicRankResult>> result = new LinkedHashMap<>();
        for (int mask = 1; mask <= 15; mask++) {
            Set<String> selected = new LinkedHashSet<>();
            for (int i = 0; i < 4; i++) if ((mask & (1 << i)) != 0) selected.add("M0" + (i + 1));
            result.put(mask, DynamicRankingEngine.INSTANCE.rank(stocks, selected));
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        Gson gson = new GsonBuilder().serializeNulls().create();
        List<StockSummary> stocks = Arrays.asList(gson.fromJson(Files.readString(Path.of(args[0])), StockSummary[].class));
        Map<Integer, Map<String, DynamicRankResult>> before = ranks(stocks);
        Map<String, ValuationBand> records = new LinkedHashMap<>();
        for (StockSummary stock : stocks) records.put(stock.getIssuerId(), ValuationBandPolicy.INSTANCE.classify(stock.getM03Per()));
        JsonObject result = new JsonObject();
        result.add("records", gson.toJsonTree(records));
        result.addProperty("rankingsUnchanged", before.equals(ranks(stocks)));
        System.out.println(gson.toJson(result));
    }
}
