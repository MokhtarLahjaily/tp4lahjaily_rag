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
import dev.langchain4j.rag.content.Content; // <-- IMPORT NÉCESSAIRE
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query; // <-- IMPORT NÉCESSAIRE
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

@ApplicationScoped
public class LlmClient implements Serializable {

    private Assistant assistant;
    private String geminiApiKey;

    // NOUVEAU : Objet pour stocker la réponse et l'info de debug
    public record RagResponse(String answer, String debugInfo) {}

    // NOUVEAU : Mettre le retriever en champ de classe
    private ContentRetriever contentRetriever;

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
                .logRequests(true)
                .logResponses(true)
                .build();

        EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();

        System.out.println("Démarrage de l'ingestion des documents...");
        EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

        List<TextSegment> ragSegments = ingestDocument("rag.pdf", embeddingModel);
        embeddingStore.addAll(embeddingModel.embedAll(ragSegments).content(), ragSegments);

        List<TextSegment> financeSegments = ingestDocument("finance.pdf", embeddingModel);
        embeddingStore.addAll(embeddingModel.embedAll(financeSegments).content(), financeSegments);

        int totalSegments = ragSegments.size() + financeSegments.size();
        System.out.println("Ingestion terminée. Total segments : " + totalSegments);

        // MODIFIÉ : Assigner au champ de classe "this.contentRetriever"
        this.contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(3)
                .minScore(0.5)
                .build();

        assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .contentRetriever(this.contentRetriever) // Utiliser le champ de classe
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();
    }

    /**
     * MODIFIÉ : Renvoie maintenant un objet RagResponse contenant la réponse ET l'info de debug.
     */
    public RagResponse ask(String prompt) {
        // 1. Obtenir la réponse de l'assistant
        String answer = assistant.chat(prompt);

        // 2. Exécuter la récupération manuellement (juste pour le debug)
        //    pour voir quels segments ont été trouvés.
        List<Content> contents = this.contentRetriever.retrieve(Query.from(prompt));
        String debugInfo = formatRetrievedContents(contents);

        // 3. Retourner les deux
        return new RagResponse(answer, debugInfo);
    }

    /**
     * NOUVEAU : Méthode helper pour formater les segments récupérés en un String lisible.
     */
    private String formatRetrievedContents(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return "Aucun segment pertinent trouvé.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("--- %d segments récupérés pour le contexte ---%n%n", contents.size()));

        for (int i = 0; i < contents.size(); i++) {
            Content content = contents.get(i);
            sb.append(String.format("--- Segment %d ---%n", i + 1));
            sb.append(content.textSegment().text());
            sb.append(String.format("%n%n"));
        }

        return sb.toString();
    }

    public void setSystemRole(String role) {
        // Non utilisé dans cette version RAG
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