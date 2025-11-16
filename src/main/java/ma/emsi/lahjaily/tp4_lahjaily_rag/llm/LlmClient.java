package ma.emsi.lahjaily.tp4_lahjaily_rag.llm;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
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
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service d'accès centralisé au LLM, configuré pour le RAG de base.
 * Utilise @ApplicationScoped pour n'ingérer les documents qu'une seule fois.
 */
@ApplicationScoped // CHANGÉ : @ApplicationScoped pour l'ingestion unique
public class LlmClient implements Serializable {

    private Assistant assistant;
    private String geminiApiKey;

    /**
     * Initialise les clés API et configure le logging.
     */
    public LlmClient() {
        geminiApiKey = System.getenv("GEMINI_KEY");
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("Erreur : variable d'environnement GEMINI_KEY absente.");
        }
        configureLogger();
    }

    /**
     * Méthode d'initialisation exécutée après la construction du bean.
     * C'est ici que nous configurons tout le pipeline RAG.
     */
    @PostConstruct
    public void init() {
        // 1. Créer le ChatModel
        ChatModel model = GoogleAiGeminiChatModel.builder()
                .apiKey(geminiApiKey)
                .modelName("gemini-2.5-flash")
                .temperature(0.3)
                .logRequests(true)
                .logResponses(true)
                .build();

        // 2. Créer le Modèle d'Embedding
        EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();

        // 3. PHASE D'INGESTION
        System.out.println("Démarrage de l'ingestion des documents...");

        // Créer un SEUL EmbeddingStore pour TOUS les documents
        EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

        // Ingestion de rag.pdf
        List<TextSegment> ragSegments = ingestDocument("rag.pdf", embeddingModel);
        embeddingStore.addAll(embeddingModel.embedAll(ragSegments).content(), ragSegments);

        // Ingestion de finance.pdf
        List<TextSegment> financeSegments = ingestDocument("finance.pdf", embeddingModel);
        embeddingStore.addAll(embeddingModel.embedAll(financeSegments).content(), financeSegments);

        int totalSegments = ragSegments.size() + financeSegments.size();
        System.out.println("Ingestion terminée. Total segments : " + totalSegments);


        // 4. CRÉER LE CONTENT RETRIEVER
        // Ce retriever unique cherche dans le magasin contenant les deux PDF
        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(3) // Récupère les 3 segments les plus pertinents
                .minScore(0.5)
                .build();

        // 5. CRÉER L'ASSISTANT FINAL
        assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .contentRetriever(contentRetriever) // <-- LE RAG EST CONNECTÉ ICI
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10)) // Mémoire de chat
                .build();
    }

    /**
     * Envoie un message au service RAG et récupère la réponse.
     */
    public String ask(String prompt) {
        return assistant.chat(prompt);
    }

    /**
     * Le rôle système est maintenant géré par le RAG.
     * Nous gardons la méthode pour la compatibilité avec BackingBean, mais elle est désactivée.
     */
    public void setSystemRole(String role) {
        // Cette méthode n'est plus nécessaire car le contexte vient du RAG.
        // On pourrait réinitialiser la mémoire ici si nécessaire.
    }


    // --- MÉTHODES HELPER (prises de rag_tests) ---

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
     * Charge, parse, et segmente un document.
     * @return Une liste de TextSegment
     */
    private static List<TextSegment> ingestDocument(String resourceName, EmbeddingModel embeddingModel) {
        Path documentPath = getPath(resourceName);
        DocumentParser parser = new ApacheTikaDocumentParser();
        Document document = FileSystemDocumentLoader.loadDocument(documentPath, parser);
        DocumentSplitter splitter = DocumentSplitters.recursive(300, 30);
        List<TextSegment> segments = splitter.split(document);
        System.out.println("Ingestion de '" + resourceName + "' terminée. " + segments.size() + " segments.");
        return segments;
    }
}