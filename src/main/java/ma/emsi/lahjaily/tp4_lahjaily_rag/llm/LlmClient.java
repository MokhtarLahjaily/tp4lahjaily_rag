package ma.emsi.lahjaily.tp4_lahjaily_rag.llm;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory; // <-- IMPORT NÉCESSAIRE
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor; // <-- IMPORT NÉCESSAIRE
import dev.langchain4j.rag.RetrievalAugmentor; // <-- IMPORT NÉCESSAIRE
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
// Imports pour le ROUTAGE
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.LanguageModelQueryRouter; // <-- IMPORT NÉCESSAIRE
import dev.langchain4j.rag.query.router.QueryRouter; // <-- IMPORT NÉCESSAIRE
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
import java.util.Collection; // <-- IMPORT NÉCESSAIRE
import java.util.HashMap; // <-- IMPORT NÉCESSAIRE
import java.util.List;
import java.util.Map; // <-- IMPORT NÉCESSAIRE
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors; // <-- IMPORT NÉCESSAIRE

@ApplicationScoped
public class LlmClient implements Serializable {

    private Assistant assistant;
    private String geminiApiKey;

    public record RagResponse(String answer, String debugInfo) {}

    // MODIFIÉ : Nous n'avons plus un seul retriever, mais le routeur
    private QueryRouter queryRouter;
    private ChatMemory chatMemory; // Pour le debug
    private EmbeddingModel embeddingModel; // Pour le debug

    public LlmClient() {
        geminiApiKey = System.getenv("GEMINI_KEY");
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("Erreur : variable d'environnement GEMINI_KEY absente.");
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

        // MODIFIÉ : Mettre en champ de classe pour le debug
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

        // --- 5. CRÉER LE ROUTEUR (Noyau de l'étape 2) ---

        // Descriptions que le LLM utilisera pour choisir
        Map<ContentRetriever, String> retrieverMap = new HashMap<>();
        retrieverMap.put(ragRetriever, "Information sur le RAG (Retrieval-Augmented Generation), LangChain4j et l'intelligence artificielle");
        retrieverMap.put(financeRetriever, "Information sur la finance, l'économie, les banques et les investissements");

        // MODIFIÉ : Assigner au champ de classe "this.queryRouter"
        this.queryRouter = new LanguageModelQueryRouter(model, retrieverMap);

        // --- 6. CRÉER L'AUGMENTOR (Nouveau composant) ---
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryRouter(this.queryRouter) // Utilise notre routeur
                .build();

        // MODIFIÉ : Mettre en champ de classe
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(10);

        // --- 7. CRÉER L'ASSISTANT FINAL (Modifié) ---
        assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                // MODIFIÉ : On utilise .retrievalAugmentor() au lieu de .contentRetriever()
                .retrievalAugmentor(retrievalAugmentor)
                .chatMemory(this.chatMemory)
                .build();
    }

    /**
     * MODIFIÉ : La logique de debug doit maintenant interroger le routeur
     */
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

    /**
     * NOUVEAU : Logique de debug pour le routeur
     */
    private String formatRouterDebug(Query query, Collection<ContentRetriever> retrievers) {
        if (retrievers == null || retrievers.isEmpty()) {
            return "--- Le routeur n'a sélectionné aucun ContentRetriever. ---";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("--- Le routeur a sélectionné %d retriever(s) ---%n%n", retrievers.size()));

        // Pour chaque retriever choisi, on récupère les segments
        for (ContentRetriever retriever : retrievers) {
            List<Content> contents = retriever.retrieve(query);
            if (contents != null && !contents.isEmpty()) {
                sb.append(String.format("--- Segments trouvés par [ %s ] ---%n", retriever.getClass().getSimpleName()));
                for (Content content : contents) {
                    sb.append(content.textSegment().text());
                    sb.append(String.format("%n---%n"));
                }
            } else {
                sb.append(String.format("--- [ %s ] n'a retourné aucun segment.%n", retriever.getClass().getSimpleName()));
            }
        }

        return sb.toString();
    }


    public void setSystemRole(String role) {
        // Non utilisé
    }

    // --- Méthodes helper (inchangées) ---
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

    /**
     * MODIFIÉ : Cette méthode ingère UN document et retourne son propre EmbeddingStore
     */
    private static EmbeddingStore<TextSegment> ingestDocument(String resourceName, EmbeddingModel embeddingModel) {
        Path documentPath = getPath(resourceName);
        DocumentParser parser = new ApacheTikaDocumentParser();
        Document document = FileSystemDocumentLoader.loadDocument(documentPath, parser);
        DocumentSplitter splitter = DocumentSplitters.recursive(300, 30);
        List<TextSegment> segments = splitter.split(document);

        // Créer un store DÉDIÉ pour ce document
        EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
        embeddingStore.addAll(embeddingModel.embedAll(segments).content(), segments);

        System.out.println("Ingestion de '" + resourceName + "' terminée. " + segments.size() + " segments.");
        return embeddingStore;
    }
}