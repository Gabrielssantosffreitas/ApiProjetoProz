package com.gabriel.apiProzProjeto;


import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@SpringBootApplication
public class ApiProzProjetoApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiProzProjetoApplication.class, args);
    }
}


record LeadRecord(
        String nome,
        String whatsapp,
        String cidade,
        String tipo,
        BigDecimal valor,
        BigDecimal parcela,
        BigDecimal lance,
        String prazoCompra,
        String objetivo,
        BigDecimal renda,
        String profissao,
        String email,
        String horario,
        String forma,
        String obs,
        boolean consentimentoContato,
        boolean aceitouPoliticaPrivacidade
) {}



@Component
class LeadStore {
    private final List<LeadRecord> leads = new CopyOnWriteArrayList<>();

    void add(LeadRecord lead) { leads.add(lead); }

    List<LeadRecord> all() { return List.copyOf(leads); }
}



@Service
class ResumoIaService {

    private final RestClient http = RestClient.create();
    private final String apiKey;
    private final String model;

    ResumoIaService(@Value("${GEMINI_API_KEY:}") String apiKey,
                    @Value("${GEMINI_MODEL:gemini-2.5-flash}") String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    String resumir(List<LeadRecord> leads) {
        if (apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Variável de ambiente GEMINI_API_KEY não configurada.");
        }

        Map<String, Object> body = Map.of("contents",
                List.of(Map.of("parts", List.of(Map.of("text", montarPrompt(leads))))));

        try {
            JsonNode resp = http.post()
                    .uri("https://generativelanguage.googleapis.com/v1beta/models/{m}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            return resp.path("candidates").path(0).path("content").path("parts").path(0).path("text")
                    .asText("Não foi possível gerar o resumo.").trim();
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Falha ao chamar a API de IA: " + e.getMessage());
        }
    }

    // Dados de contato (nome, whatsapp, email) NÃO são enviados à IA — só o perfil comercial.
    private String montarPrompt(List<LeadRecord> leads) {
        String perfis = leads.stream().map(l -> String.format(
                "- Cidade: %s | Tipo: %s | Crédito desejado: R$ %s | Parcela: R$ %s | Lance: R$ %s | "
                        + "Prazo: %s | Objetivo: %s | Renda: R$ %s | Profissão: %s | Obs: %s",
                l.cidade(), l.tipo(), l.valor(), l.parcela(), l.lance(),
                l.prazoCompra(), l.objetivo(), l.renda(), l.profissao(), l.obs()
        )).collect(Collectors.joining("\n"));

        return "Você é um analista comercial de consórcios. Resuma em 2 a 3 frases, em português, "
                + "o perfil financeiro/comercial dos leads abaixo, destacando capacidade de pagamento, "
                + "urgência e potencial de conversão.\n\n" + perfis;
    }
}



@RestController
@RequestMapping("/api/leads")
class LeadController {

    private final LeadStore store;
    private final ResumoIaService ia;

    LeadController(LeadStore store, ResumoIaService ia) {
        this.store = store;
        this.ia = ia;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> criar(@RequestBody LeadRecord lead) {
        if (!lead.aceitouPoliticaPrivacidade()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "É necessário aceitar a política de privacidade.");
        }
        store.add(lead);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("mensagem", "Lead cadastrado com sucesso!", "total", store.all().size()));
    }

    @GetMapping
    List<LeadRecord> listar() {
        return store.all();
    }

    @GetMapping("/resumo")
    Map<String, String> resumo() {
        List<LeadRecord> leads = store.all();
        if (leads.isEmpty()) {
            return Map.of("resumo", "Nenhum lead cadastrado ainda.");
        }
        return Map.of("resumo", ia.resumir(leads));
    }

    @Configuration
    class CorsConfig implements WebMvcConfigurer {
        @Override
        public void addCorsMappings(CorsRegistry registry) {
            registry.addMapping("/api/**")
                    .allowedOrigins("*")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*");
        }
    }
}
