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

/**
 * Bean managé JSF pour la page index.xhtml
 * Gère la logique d’interaction entre l’utilisateur et le modèle LLM.
 *
 * @author Walid
 */
@Named("bb")
@ViewScoped
public class BackingBean implements Serializable {

    private String roleSysteme;
    private boolean roleSystemeChangeable = true;
    private String question;
    private String reponse;
    private final StringBuilder conversation = new StringBuilder();

    @Inject
    private LlmClient llm;

    public String envoyer() {
        if (question == null || question.isBlank()) {
            afficherMessage(FacesMessage.SEVERITY_ERROR, "Texte manquant", "Veuillez entrer une question.");
            return null;
        }

        try {
            // Définition du rôle système au premier appel
            if (roleSystemeChangeable) {
                llm.setSystemRole(roleSysteme);
                roleSystemeChangeable = false;
            }

            reponse = llm.ask(question);
            enregistrerEchange(question, reponse);

        } catch (Exception e) {
            reponse = null;
            afficherMessage(FacesMessage.SEVERITY_ERROR, "Erreur LLM", e.getMessage());
            e.printStackTrace();
        }

        return null;
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
            String role = "You are a helpful assistant. You help the user find information and explain step by step when asked.";
            rolesDisponibles.add(new SelectItem(role, "Assistant"));

            role = "You are an interpreter. Translate French ↔ English. If 1–3 words, add usage examples.";
            rolesDisponibles.add(new SelectItem(role, "Traducteur Anglais–Français"));

            role = "You are a travel guide. When given a country/city, list top places to visit and typical meal price.";
            rolesDisponibles.add(new SelectItem(role, "Guide touristique"));

        }
        return rolesDisponibles;
    }

    public String getRoleSysteme() {
        return roleSysteme;
    }

    public void setRoleSysteme(String roleSysteme) {
        this.roleSysteme = roleSysteme;
    }

    public boolean isRoleSystemeChangeable() {
        return roleSystemeChangeable;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getReponse() {
        return reponse;
    }

    public void setReponse(String reponse) {
        this.reponse = reponse;
    }

    public String getConversation() {
        return conversation.toString();
    }

    public void setConversation(String conversationTexte) {
        conversation.setLength(0);
        conversation.append(conversationTexte);
    }

}