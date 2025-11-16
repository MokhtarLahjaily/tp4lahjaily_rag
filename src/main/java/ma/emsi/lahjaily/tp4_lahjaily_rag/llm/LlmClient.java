package ma.emsi.lahjaily.tp4_lahjaily_rag.llm;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.WebSearchContentRetriever;
import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.tavily.TavilyWebSearchEngine;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.LanguageModelQueryRouter;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;


@ApplicationScoped
public class LlmClient implements Serializable {

    private Assistant assistant;
    private String geminiApiKey;
    private String tavilyApiKey;

    public record RagResponse(String answer, String debugInfo) {}


    private QueryRouter queryRouter;
    private ChatMemory chatMemory;
    private EmbeddingModel embeddingModel;

    public LlmClient() {
        geminiApiKey = System.getenv("GEMINI_KEY");
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("Erreur : variable d'environnement GEMINI_KEY absente.");
        }
        tavilyApiKey = System.getenv("TAVILY_KEY");
        if (tavilyApiKey == null || tavilyApiKey.isBlank()) {
            throw new IllegalStateException("Erreur : variable d'environnement TAVILY_KEY absente pour la recherche web.");
        }
        configureLogger();
    }

    @PostConstruct
    public void init() {
        ChatModel model = GoogleAiGeminiChatModel.builder()
                .apiKey(geminiApiKey)
                .modelName("gemini-2.5-flash")
                .temperature(0.3)
                .logRequests(true) // Le JSON sera visible dans les logs serveur
                .logResponses(true)
                .build();

        this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();

        // --- 3. PHASE D'INGESTION (SÉPARÉE) ---
        System.out.println("Démarrage de l'ingestion des documents (séparés)...");

        // Magasin 1 : RAG
        EmbeddingStore<TextSegment> ragStore = ingestDocument("rag.pdf", this.embeddingModel);

        // Magasin 2 : Finance
        EmbeddingStore<TextSegment> financeStore = ingestDocument("finance.pdf", this.embeddingModel);

        System.out.println("Ingestion terminée.");

        // --- 4. CRÉER LES CONTENT RETRIEVERS (SÉPARÉS) ---

        // Retriever 1 : RAG
        ContentRetriever ragRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(ragStore)
                .embeddingModel(this.embeddingModel)
                .maxResults(2) // 2 segments pertinents de ce PDF
                .build();

        // Retriever 2 : Finance
        ContentRetriever financeRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(financeStore)
                .embeddingModel(this.embeddingModel)
                .maxResults(2) // 2 segments pertinents de ce PDF
                .build();

        // Retriever 3 : Web Search
        WebSearchEngine webSearchEngine = TavilyWebSearchEngine.builder()
                .apiKey(tavilyApiKey)
                .build();

        ContentRetriever webRetriever = WebSearchContentRetriever.builder()
                .webSearchEngine(webSearchEngine)
                .build();

        // --- 5. CRÉER LE ROUTEUR (Noyau de l'étape 2) ---

        // Descriptions que le LLM utilisera pour choisir
        Map<ContentRetriever, String> retrieverMap = new HashMap<>();
        retrieverMap.put(ragRetriever, "Information sur le RAG (Retrieval-Augmented Generation), LangChain4j et l'intelligence artificielle");
        retrieverMap.put(financeRetriever, "Information sur la finance, l'économie, les banques et les investissements");
        retrieverMap.put(webRetriever, "Informations d'actualité, événements récents, ou sujets généraux non couverts par les documents PDF (comme la météo, le sport, etc.)");
        this.queryRouter = new LanguageModelQueryRouter(model, retrieverMap);

        // --- 6. CRÉER L'AUGMENTOR ---
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryRouter(this.queryRouter) // Utilise notre routeur
                .build();
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(10);

        // --- 7. CRÉER L'ASSISTANT FINAL ---
        assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .retrievalAugmentor(retrievalAugmentor)
                .chatMemory(this.chatMemory)
                .build();
    }

    public RagResponse ask(String prompt) {
        // 1. Obtenir la réponse de l'assistant
        String answer = assistant.chat(prompt);

        // 2. Logique de debug :
        //    Demander manuellement au routeur quels retrievers il choisirait
        Query query = Query.from(prompt);
        Collection<ContentRetriever> retrievers = this.queryRouter.route(query);
        String debugInfo = formatRouterDebug(query, retrievers);

        // 3. Retourner les deux
        return new RagResponse(answer, debugInfo);
    }

    private String formatRouterDebug(Query query, Collection<ContentRetriever> retrievers) {
        if (retrievers == null || retrievers.isEmpty()) {
            return "--- Le routeur n'a sélectionné aucun ContentRetriever. ---";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("--- Le routeur a sélectionné %d retriever(s) ---%n%n", retrievers.size()));

        for (ContentRetriever retriever : retrievers) {
            String retrieverName = retriever.getClass().getSimpleName();
            if (retriever instanceof WebSearchContentRetriever) {
                retrieverName = "WebSearchRetriever(Tavily)";
            }

            List<Content> contents = retriever.retrieve(query);
            if (contents != null && !contents.isEmpty()) {
                sb.append(String.format("--- Segments trouvés par [ %s ] ---%n", retrieverName));
                for (Content content : contents) {

                    // L'accès au texte se fait toujours via .textSegment().text()
                    String text = content.textSegment().text();
                    // --------------------

                    sb.append(text);
                    sb.append(String.format("%n---%n"));
                }
            } else {
                sb.append(String.format("--- [ %s ] n'a retourné aucun segment.%n", retrieverName));
            }
        }

        return sb.toString();
    }

    // --- Méthodes helper ---
    private static void configureLogger() {
        Logger packageLogger = Logger.getLogger("dev.langchain4j");
        packageLogger.setLevel(Level.FINE);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.FINE);
        packageLogger.addHandler(handler);
    }

    private static Path getPath(String fileName) {
        try {
            URI fileUri = Thread.currentThread().getContextClassLoader().getResource(fileName).toURI();
            return Paths.get(fileUri);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Impossible de trouver le fichier " + fileName, e);
        }
    }

    private static EmbeddingStore<TextSegment> ingestDocument(String resourceName, EmbeddingModel embeddingModel) {
        Path documentPath = getPath(resourceName);
        DocumentParser parser = new ApacheTikaDocumentParser();
        Document document = FileSystemDocumentLoader.loadDocument(documentPath, parser);
        DocumentSplitter splitter = DocumentSplitters.recursive(300, 30);
        List<TextSegment> segments = splitter.split(document);
        EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
        embeddingStore.addAll(embeddingModel.embedAll(segments).content(), segments);

        System.out.println("Ingestion de '" + resourceName + "' terminée. " + segments.size() + " segments.");
        return embeddingStore;
    }
}