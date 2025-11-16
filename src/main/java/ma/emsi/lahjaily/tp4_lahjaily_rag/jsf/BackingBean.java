package ma.emsi.lahjaily.tp4_lahjaily_rag.jsf;

import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.model.SelectItem;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import ma.emsi.lahjaily.tp4_lahjaily_rag.llm.LlmClient;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Named("bb")
@ViewScoped
public class BackingBean implements Serializable {

    private String roleSysteme = "RAG-Router-Web";
    private boolean roleSystemeChangeable = false;

    private String question;
    private String reponse;
    private final StringBuilder conversation = new StringBuilder();


    private boolean debug = false;
    private String debugInfo = ""; // Sera utilisé pour les segments récupérés

    @Inject
    private LlmClient llm;

    public String envoyer() {
        if (question == null || question.isBlank()) {
            afficherMessage(FacesMessage.SEVERITY_ERROR, "Texte manquant", "Veuillez entrer une question.");
            return null;
        }

        try {
            // Récupérer l'objet RagResponse
            LlmClient.RagResponse ragResponse = llm.ask(question);

            this.reponse = ragResponse.answer(); // Extraire la réponse
            this.debugInfo = ragResponse.debugInfo(); // Extraire l'info de debug

            enregistrerEchange(question, reponse);

        } catch (Exception e) {
            reponse = null;
            this.debugInfo = "Erreur lors de l'exécution : " + e.getMessage(); // Mettre l'erreur dans le debug
            afficherMessage(FacesMessage.SEVERITY_ERROR, e.getClass().getSimpleName(), e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    public boolean isDebug() {
        return debug;
    }

    public void toggleDebug() {
        this.debug = !this.debug;
    }

    public String getDebugInfo() {
        return debugInfo;
    }

    public void setDebugInfo(String debugInfo) {
        this.debugInfo = debugInfo;
    }

    public String nouveauChat() {
        return "index?faces-redirect=true";
    }

    private void enregistrerEchange(String q, String r) {
        conversation
                .append("== Utilisateur :\n").append(q).append("\n")
                .append("== Assistant :\n").append(r).append("\n\n");
    }

    private void afficherMessage(FacesMessage.Severity type, String resume, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(type, resume, detail));
    }

    private List<SelectItem> rolesDisponibles;

    public List<SelectItem> getRolesSysteme() {
        if (rolesDisponibles == null) {
            rolesDisponibles = new ArrayList<>();
            String role = "RAG-Router-Web";
            rolesDisponibles.add(new SelectItem(role, "RAG (Finance/IA) + Web"));
        }
        return rolesDisponibles;
    }

    // --- Getters et Setters ---
    public String getRoleSysteme() { return roleSysteme; }
    public void setRoleSysteme(String roleSysteme) { this.roleSysteme = roleSysteme; }
    public boolean isRoleSystemeChangeable() { return roleSystemeChangeable; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getReponse() { return reponse; }
    public void setReponse(String reponse) { this.reponse = reponse; }
    public String getConversation() { return conversation.toString(); }
    public void setConversation(String conversationTexte) {
        conversation.setLength(0);
        conversation.append(conversationTexte);
    }
}