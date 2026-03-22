# ============================================================
# Script d installation des Skills Claude Code
# Spring Boot + React TypeScript
# Usage : .\install-skills.ps1
# ============================================================

 = ":USERPROFILE\.claude\skills"

Write-Host "Creation du dossier skills : " -ForegroundColor Cyan


New-Item -ItemType Directory -Force -Path "\spring-boot" | Out-Null
Set-Content -Path "\spring-boot\SKILL.md" -Encoding UTF8 -Value @'
---
name: spring-boot
description: >
  Conventions et bonnes pratiques pour le développement Spring Boot. Utilise ce skill
  dès que l'utilisateur mentionne Spring Boot, Spring Security, JPA, Hibernate, REST API Java,
  Maven, des entités, des repositories, des services Java, des contrôleurs REST, la gestion
  d'exceptions Java, des tests Spring, ou tout développement backend Java. Déclenche aussi
  pour les questions sur les DTOs, les configurations application.yml, ou les microservices Java.
---

# Spring Boot Skill

## Structure des packages

Toujours organiser selon cette hiérarchie :

```
com.monapp/
├── config/          # Beans de configuration (@Configuration)
├── controller/      # @RestController — couche HTTP uniquement
├── service/         # @Service — logique métier
│   └── impl/        # Implémentations des interfaces de service
├── repository/      # @Repository extends JpaRepository<Entity, ID>
├── entity/          # @Entity JPA — jamais exposées directement
├── dto/
│   ├── request/     # DTOs entrants (ex: CreateUserRequest)
│   └── response/    # DTOs sortants (ex: UserResponse)
├── mapper/          # MapStruct ou mappers manuels Entity ↔ DTO
├── exception/       # Exceptions métier + GlobalExceptionHandler
└── security/        # Config Spring Security, JWT filter, etc.
```

## Règles fondamentales

### Contrôleurs
- Retourner toujours `ResponseEntity<?>` avec le bon code HTTP
- Ne contenir **aucune** logique métier — déléguer au service
- Valider les inputs avec `@Valid` et `@Validated`
- Annoter avec `@RequestMapping("/api/v1/resource")`

```java
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.findById(id));
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

### Services
- Toujours définir une interface + une implémentation
- Annoter les méthodes de lecture avec `@Transactional(readOnly = true)`
- Annoter les méthodes d'écriture avec `@Transactional`
- Lancer des exceptions métier spécifiques (jamais de retour null)

```java
public interface UserService {
    UserResponse findById(Long id);
    UserResponse create(CreateUserRequest request);
    void delete(Long id);
}

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Override
    @Transactional(readOnly = true)
    public UserResponse findById(Long id) {
        return userRepository.findById(id)
            .map(userMapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }
}
```

### Entités JPA
- Ne jamais exposer l'entité dans les réponses HTTP
- Utiliser `@Column(nullable = false)` explicitement
- Préférer `FetchType.LAZY` pour les relations
- Éviter `@Data` Lombok sur les entités (risque de StackOverflow avec `equals/hashCode`)

```java
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String firstName;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
```

### DTOs
- Séparer Request et Response
- Valider les requests avec les annotations Bean Validation

```java
public record CreateUserRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 2, max = 50) String firstName,
    @NotBlank @Size(min = 8) String password
) {}

public record UserResponse(
    Long id,
    String email,
    String firstName,
    LocalDateTime createdAt
) {}
```

### Gestion des exceptions
- Créer une exception de base + des exceptions spécifiques
- Centraliser avec `@RestControllerAdvice`

```java
// Exception de base
public class AppException extends RuntimeException {
    private final HttpStatus status;
    public AppException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }
}

// Exception spécifique
public class ResourceNotFoundException extends AppException {
    public ResourceNotFoundException(String resource, Long id) {
        super(resource + " not found with id: " + id, HttpStatus.NOT_FOUND);
    }
}

// Handler global
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse(ex.getMessage(), HttpStatus.NOT_FOUND.value()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(new ErrorResponse(message, 400));
    }
}
```

## Configuration application.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/${DB_NAME}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate          # En prod : validate. En dev : update
    show-sql: false
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.PostgreSQLDialect

  security:
    jwt:
      secret: ${JWT_SECRET}
      expiration: 86400000        # 24h en ms

server:
  port: 8080
  servlet:
    context-path: /api

logging:
  level:
    com.monapp: DEBUG
    org.springframework.security: INFO
```

## Spring Security + JWT

- Créer un `JwtAuthFilter extends OncePerRequestFilter`
- Désactiver CSRF (API stateless)
- Configurer CORS pour le frontend React

```java
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
```

## Tests

### Test unitaire de service (Mockito)
```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserMapper userMapper;
    @InjectMocks private UserServiceImpl userService;

    @Test
    void findById_shouldThrow_whenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> userService.findById(99L));
    }
}
```

### Test d'intégration du contrôleur (MockMvc)
```java
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private UserService userService;

    @Test
    void getById_shouldReturn200() throws Exception {
        when(userService.findById(1L)).thenReturn(new UserResponse(1L, "a@b.com", "Alice", now()));

        mockMvc.perform(get("/api/v1/users/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("a@b.com"));
    }
}
```

## Dépendances Maven essentielles

```xml
<dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-api</artifactId><version>0.12.3</version></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
    <dependency><groupId>org.mapstruct</groupId><artifactId>mapstruct</artifactId><version>1.5.5.Final</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
</dependencies>
```

## Checklist avant tout commit

- [ ] Aucune entité exposée directement dans un contrôleur
- [ ] Toutes les routes publiques explicitement déclarées dans SecurityConfig
- [ ] Variables sensibles en variables d'environnement (jamais hardcodées)
- [ ] Tests unitaires pour chaque méthode de service
- [ ] `@Transactional` présent sur les méthodes d'écriture
- [ ] Validation des inputs avec `@Valid`

'@

Write-Host "  spring-boot OK" -ForegroundColor Yellow

New-Item -ItemType Directory -Force -Path "\react-typescript" | Out-Null
Set-Content -Path "\react-typescript\SKILL.md" -Encoding UTF8 -Value @'
---
name: react-typescript
description: >
  Conventions et bonnes pratiques pour le développement React avec TypeScript. Utilise ce skill
  dès que l'utilisateur mentionne React, TypeScript, composants, hooks, state management,
  React Query, Axios, Context API, Zustand, Redux, routing React, formulaires React,
  Vite, Next.js, ou tout développement frontend basé sur React. Déclenche aussi pour
  les questions sur les appels API depuis React, la gestion des erreurs frontend,
  les tests React, ou l'organisation d'un projet React.
---

# React TypeScript Skill

## Structure du projet (Vite + React + TS)

```
src/
├── api/             # Fonctions d'appel API (axios instances + endpoints)
├── assets/          # Images, fonts, fichiers statiques
├── components/
│   ├── ui/          # Composants génériques réutilisables (Button, Input, Modal...)
│   └── features/    # Composants liés à une feature métier
├── hooks/           # Hooks personnalisés (useAuth, useDebounce...)
├── pages/           # Composants de page, un par route
├── store/           # Zustand ou Redux slices
├── types/           # Interfaces et types TypeScript globaux
├── utils/           # Fonctions utilitaires pures
├── router/          # Configuration React Router
└── main.tsx
```

## Règles fondamentales

### Composants
- Toujours des **fonctions** (jamais de classes)
- Un composant = un fichier, nommé en **PascalCase**
- Typer toutes les props avec une `interface` ou `type`
- Exporter en **export nommé** (pas `export default` sauf pour les pages)

```tsx
// ✅ Bon
interface UserCardProps {
  userId: number;
  displayName: string;
  avatarUrl?: string;
  onSelect: (id: number) => void;
}

export function UserCard({ userId, displayName, avatarUrl, onSelect }: UserCardProps) {
  return (
    <div onClick={() => onSelect(userId)} className="user-card">
      {avatarUrl && <img src={avatarUrl} alt={displayName} />}
      <span>{displayName}</span>
    </div>
  );
}
```

### Typage TypeScript
- Typer les réponses API avec des interfaces dédiées
- Utiliser `type` pour les unions/intersections, `interface` pour les objets
- Éviter `any` — préférer `unknown` si le type est incertain
- Utiliser des **types utilitaires** : `Partial<T>`, `Pick<T,K>`, `Omit<T,K>`

```ts
// types/user.ts
export interface User {
  id: number;
  email: string;
  firstName: string;
  createdAt: string;
}

export type CreateUserPayload = Omit<User, 'id' | 'createdAt'> & {
  password: string;
};

export type UserSummary = Pick<User, 'id' | 'email' | 'firstName'>;
```

## Appels API avec Axios + React Query

### Configuration Axios

```ts
// api/axiosInstance.ts
import axios from 'axios';

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
});

// Intercepteur : ajoute le JWT automatiquement
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// Intercepteur : redirige vers /login si 401
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);
```

### Fonctions API

```ts
// api/users.ts
import { api } from './axiosInstance';
import type { User, CreateUserPayload } from '../types/user';

export const usersApi = {
  getById: (id: number) =>
    api.get<User>(`/users/${id}`).then((r) => r.data),

  getAll: () =>
    api.get<User[]>('/users').then((r) => r.data),

  create: (payload: CreateUserPayload) =>
    api.post<User>('/users', payload).then((r) => r.data),

  delete: (id: number) =>
    api.delete(`/users/${id}`),
};
```

### Hooks React Query

```tsx
// hooks/useUsers.ts
import { useQuery, useMutation, useQueryClient } from ' @tanstack/react-query';
import { usersApi } from '../api/users';
import type { CreateUserPayload } from '../types/user';

export const userKeys = {
  all: ['users'] as const,
  byId: (id: number) => ['users', id] as const,
};

export function useUser(id: number) {
  return useQuery({
    queryKey: userKeys.byId(id),
    queryFn: () => usersApi.getById(id),
    staleTime: 5 * 60 * 1000, // 5 min
  });
}

export function useCreateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: CreateUserPayload) => usersApi.create(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: userKeys.all });
    },
  });
}
```

### Utilisation dans un composant

```tsx
function UserDetail({ id }: { id: number }) {
  const { data: user, isLoading, error } = useUser(id);

  if (isLoading) return <Spinner />;
  if (error) return <ErrorMessage message="Impossible de charger l'utilisateur" />;

  return <UserCard user={user} />;
}
```

## Hooks personnalisés

Extraire la logique dans des hooks custom dès qu'elle est réutilisée ou complexe :

```ts
// hooks/useDebounce.ts
export function useDebounce<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);
  return debounced;
}

// hooks/useLocalStorage.ts
export function useLocalStorage<T>(key: string, defaultValue: T) {
  const [value, setValue] = useState<T>(() => {
    try {
      const stored = localStorage.getItem(key);
      return stored ? JSON.parse(stored) : defaultValue;
    } catch {
      return defaultValue;
    }
  });

  const setStoredValue = (val: T) => {
    setValue(val);
    localStorage.setItem(key, JSON.stringify(val));
  };

  return [value, setStoredValue] as const;
}
```

## Gestion des formulaires (React Hook Form + Zod)

```tsx
import { useForm } from 'react-hook-form';
import { zodResolver } from ' @hookform/resolvers/zod';
import { z } from 'zod';

const loginSchema = z.object({
  email: z.string().email('Email invalide'),
  password: z.string().min(8, 'Minimum 8 caractères'),
});

type LoginFormData = z.infer<typeof loginSchema>;

export function LoginForm() {
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<LoginFormData>({
    resolver: zodResolver(loginSchema),
  });

  const onSubmit = async (data: LoginFormData) => {
    // appel API ici
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)}>
      <input {...register('email')} type="email" />
      {errors.email && <p>{errors.email.message}</p>}
      <input {...register('password')} type="password" />
      {errors.password && <p>{errors.password.message}</p>}
      <button type="submit" disabled={isSubmitting}>Connexion</button>
    </form>
  );
}
```

## State management avec Zustand

```ts
// store/authStore.ts
import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface AuthState {
  token: string | null;
  user: UserSummary | null;
  setAuth: (token: string, user: UserSummary) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      setAuth: (token, user) => set({ token, user }),
      logout: () => set({ token: null, user: null }),
    }),
    { name: 'auth-storage' }
  )
);
```

## Variables d'environnement

```bash
# .env
VITE_API_BASE_URL=http://localhost:8080/api/v1
VITE_APP_NAME=MonApp
```

```ts
// Toujours préfixer par VITE_ pour Vite
const apiUrl = import.meta.env.VITE_API_BASE_URL;
```

## Dépendances essentielles

```json
{
  "dependencies": {
    "react": "^18",
    "react-dom": "^18",
    "react-router-dom": "^6",
    "@tanstack/react-query": "^5",
    "axios": "^1",
    "zustand": "^4",
    "react-hook-form": "^7",
    "zod": "^3",
    "@hookform/resolvers": "^3"
  },
  "devDependencies": {
    "typescript": "^5",
    "vite": "^5",
    "@vitejs/plugin-react": "^4",
    "@types/react": "^18",
    "vitest": "^1",
    "@testing-library/react": "^14",
    "@testing-library/user-event": "^14"
  }
}
```

## Checklist avant commit

- [ ] Pas de `any` dans le code TypeScript
- [ ] Toutes les props typées avec une interface
- [ ] Gestion des états `isLoading` et `error` dans chaque composant qui fetch
- [ ] Variables d'environnement via `import.meta.env`
- [ ] Aucune logique métier dans les composants (extraite dans des hooks)
- [ ] Formulaires validés avec Zod

'@

Write-Host "  react-typescript OK" -ForegroundColor Yellow

New-Item -ItemType Directory -Force -Path "\fullstack-conventions" | Out-Null
Set-Content -Path "\fullstack-conventions\SKILL.md" -Encoding UTF8 -Value @'
---
name: fullstack-conventions
description: >
  Conventions de cohérence entre Spring Boot (backend) et React TypeScript (frontend).
  Utilise ce skill dès que l'utilisateur travaille sur l'intégration entre les deux couches :
  appels API depuis React vers Spring Boot, configuration CORS, gestion des tokens JWT,
  alignement des types DTO/TypeScript, gestion des erreurs HTTP, contrat d'API REST,
  authentification fullstack, ou toute question qui touche à la fois le backend Java
  et le frontend React. Déclenche aussi pour les questions sur OpenAPI/Swagger,
  la génération de types depuis le backend, ou le debug d'appels réseau entre les deux couches.
---

# Fullstack Conventions — Spring Boot + React TypeScript

## Principe directeur

Le backend définit le contrat (DTOs + codes HTTP). Le frontend s'y conforme strictement.
Toute modification du contrat d'API doit être répercutée des deux côtés simultanément.

---

## 1. Alignement des types DTO ↔ TypeScript

### Convention de nommage

| Backend Java (camelCase) | Frontend TypeScript |
|--------------------------|---------------------|
| `firstName`              | `firstName`         |
| `createdAt`              | `createdAt`         |
| `userId`                 | `userId`            |

Spring Boot sérialise en camelCase par défaut avec Jackson — ne pas changer ce comportement.

### Exemple de correspondance

**Backend — DTO Java :**
```java
public record UserResponse(
    Long id,
    String email,
    String firstName,
    LocalDateTime createdAt
) {}
```

**Frontend — Type TypeScript correspondant :**
```ts
// types/user.ts
export interface UserResponse {
  id: number;        // Long Java → number TS
  email: string;
  firstName: string;
  createdAt: string; // LocalDateTime Java → string ISO 8601 côté TS
}
```

**Règles de correspondance des types :**

| Java          | TypeScript       |
|---------------|------------------|
| `Long`, `Integer` | `number`     |
| `String`      | `string`         |
| `Boolean`     | `boolean`        |
| `LocalDateTime`, `LocalDate` | `string` (ISO 8601) |
| `List<T>`     | `T[]`            |
| `Optional<T>` | `T \| null`      |
| `Map<String, V>` | `Record<string, V>` |

---

## 2. Configuration CORS

**Backend — SecurityConfig.java :**
```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(
        "http://localhost:5173",          // Dev Vite
        "https://monapp.com"              // Prod
    ));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
}
```

**Frontend — Vite proxy (dev uniquement) :**
```ts
// vite.config.ts
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
```

En développement avec le proxy Vite, `VITE_API_BASE_URL=/api` (pas besoin de CORS).
En production, CORS doit être configuré côté Spring Boot.

---

## 3. Authentification JWT — Flux complet

### Flux de connexion

```
React                          Spring Boot
  |                                |
  |  POST /api/v1/auth/login       |
  |  { email, password }  -------> |
  |                                |  Valide credentials
  |                                |  Génère JWT
  |  <------- { token, user } -----|
  |                                |
  |  Stocke token (Zustand+LS)     |
  |  Authorization: Bearer <token> |
  |  GET /api/v1/users/me  ------> |
  |                                |  Valide JWT
  |  <------- UserResponse --------|
```

### Backend — Endpoint d'authentification

```java
@PostMapping("/auth/login")
public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    // ...
    return ResponseEntity.ok(new AuthResponse(token, userResponse));
}

public record AuthResponse(String token, UserResponse user) {}
```

### Frontend — Gestion du token

```ts
// api/auth.ts
export const authApi = {
  login: (credentials: LoginRequest) =>
    api.post<AuthResponse>('/auth/login', credentials).then(r => r.data),
  
  me: () =>
    api.get<UserResponse>('/auth/me').then(r => r.data),
};

// store/authStore.ts — voir react-typescript skill
// L'intercepteur Axios ajoute le token automatiquement — voir react-typescript skill
```

---

## 4. Convention de gestion des erreurs HTTP

### Backend — Format d'erreur uniforme

```java
public record ErrorResponse(
    String message,
    int status,
    String path,
    LocalDateTime timestamp
) {}
```

Tous les endpoints d'erreur retournent ce format via le `GlobalExceptionHandler`.

### Frontend — Interception et affichage

```ts
// utils/apiError.ts
export interface ApiError {
  message: string;
  status: number;
  path: string;
  timestamp: string;
}

export function getErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as ApiError | undefined;
    return data?.message ?? 'Une erreur inattendue est survenue';
  }
  return 'Une erreur inattendue est survenue';
}

// Utilisation dans un composant
const { mutate, error } = useCreateUser();
if (error) return <Alert>{getErrorMessage(error)}</Alert>;
```

### Mapping des codes HTTP

| Code | Situation backend | Comportement frontend |
|------|-------------------|-----------------------|
| 200  | OK                | Afficher les données |
| 201  | Created           | Rediriger ou confirmer |
| 204  | No Content        | Confirmer la suppression |
| 400  | Validation échouée | Afficher les erreurs de champ |
| 401  | Token invalide/expiré | Rediriger vers /login |
| 403  | Accès refusé      | Afficher page 403 |
| 404  | Ressource introuvable | Afficher page 404 |
| 422  | Erreur métier     | Afficher le message d'erreur |
| 500  | Erreur serveur    | Message générique + log |

---

## 5. Convention de nommage des endpoints REST

| Action         | Méthode | URL                       |
|----------------|---------|---------------------------|
| Lister         | GET     | `/api/v1/users`           |
| Récupérer un   | GET     | `/api/v1/users/{id}`      |
| Créer          | POST    | `/api/v1/users`           |
| Modifier tout  | PUT     | `/api/v1/users/{id}`      |
| Modifier partiel | PATCH | `/api/v1/users/{id}`      |
| Supprimer      | DELETE  | `/api/v1/users/{id}`      |
| Sous-ressource | GET     | `/api/v1/users/{id}/posts`|

**Règles :**
- Toujours en **minuscules** et **kebab-case** : `/api/v1/user-profiles`
- Toujours des **noms** (jamais de verbes) : `/users` pas `/getUsers`
- Versionner l'API : `/api/v1/...`
- Pluriel pour les collections : `/users`, `/posts`

---

## 6. Pagination

### Backend
```java
@GetMapping
public ResponseEntity<Page<UserResponse>> getAll(
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "20") int size,
    @RequestParam(defaultValue = "createdAt") String sortBy
) {
    Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).descending());
    return ResponseEntity.ok(userService.findAll(pageable));
}
```

### Frontend
```ts
// types/pagination.ts
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;       // page courante (0-indexed)
  size: number;
  first: boolean;
  last: boolean;
}

// hooks/useUsers.ts
export function useUsers(page = 0, size = 20) {
  return useQuery({
    queryKey: ['users', { page, size }],
    queryFn: () => usersApi.getAll({ page, size }),
  });
}
```

---

## 7. Variables d'environnement multi-environnements

### Backend — application.yml par profil
```yaml
# application.yml (commun)
spring:
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}

# application-dev.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myapp_dev

# application-prod.yml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}
```

### Frontend — fichiers .env Vite
```bash
# .env.development
VITE_API_BASE_URL=http://localhost:8080/api/v1

# .env.production
VITE_API_BASE_URL=https://api.monapp.com/api/v1
```

---

## 8. Documentation API avec Springdoc OpenAPI

Ajouter la dépendance :
```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.3.0</version>
</dependency>
```

Accessible sur : `http://localhost:8080/swagger-ui.html`

Le frontend peut utiliser la spec OpenAPI générée pour valider le contrat.

---

## Checklist d'intégration

- [ ] CORS configuré pour les origines frontend (dev + prod)
- [ ] Format d'erreur identique sur tous les endpoints
- [ ] Toutes les dates sérialisées en ISO 8601 (configurer Jackson)
- [ ] Token JWT envoyé dans le header `Authorization: Bearer <token>`
- [ ] Types TypeScript alignés avec les DTOs Java
- [ ] Endpoints versionnés `/api/v1/`
- [ ] Swagger/OpenAPI accessible en dev
- [ ] Variables d'environnement séparées par profil (dev/prod)

'@

Write-Host "  fullstack-conventions OK" -ForegroundColor Yellow

New-Item -ItemType Directory -Force -Path "\docker-compose-springboot" | Out-Null
Set-Content -Path "\docker-compose-springboot\SKILL.md" -Encoding UTF8 -Value @'
---
name: docker-compose-springboot
description: >
  Conventions Docker et Docker Compose pour containeriser une application Spring Boot + React.
  Utilise ce skill dès que l'utilisateur mentionne Docker, docker-compose, Dockerfile,
  containerisation, déploiement, images Docker, volumes, réseaux Docker, variables
  d'environnement Docker, ou veut lancer son application Spring Boot ou React dans des conteneurs.
  Déclenche aussi pour les questions sur le build multi-stage, les registres d'images,
  ou la configuration d'environnements de développement containerisés.
---

# Docker Compose — Spring Boot + React

## Architecture des conteneurs

```
┌─────────────────────────────────────────────┐
│              Docker Network                  │
│                                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │  nginx   │  │ backend  │  │ postgres │  │
│  │  :80     │  │  :8080   │  │  :5432   │  │
│  │ (React)  │  │(Spring)  │  │  (DB)    │  │
│  └──────────┘  └──────────┘  └──────────┘  │
│       ↑               ↑                     │
└───────┼───────────────┼─────────────────────┘
        │               │
     :80 (public)    :8080 (public dev seulement)
```

---

## Structure des fichiers

```
projet/
├── backend/              # Projet Spring Boot
│   ├── Dockerfile
│   └── src/
├── frontend/             # Projet React/Vite
│   ├── Dockerfile
│   ├── nginx.conf
│   └── src/
├── docker-compose.yml         # Production
├── docker-compose.dev.yml     # Développement (override)
└── .env                       # Variables d'environnement
```

---

## Dockerfile Backend (Spring Boot)

```dockerfile
# backend/Dockerfile
# ─── Étape 1 : Build Maven ───────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copier d'abord les fichiers de dépendances pour profiter du cache Docker
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

# Copier les sources et builder
COPY src ./src
RUN ./mvnw package -DskipTests -q

# ─── Étape 2 : Image finale légère ──────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Créer un utilisateur non-root
RUN addgroup -S spring && adduser -S spring -G spring
USER spring

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
```

---

## Dockerfile Frontend (React + Nginx)

```dockerfile
# frontend/Dockerfile
# ─── Étape 1 : Build Vite ────────────────────────────────────────
FROM node:20-alpine AS builder

WORKDIR /app

COPY package*.json .
RUN npm ci --frozen-lockfile

COPY . .

# ARG pour injecter l'URL de l'API au moment du build
ARG VITE_API_BASE_URL
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

RUN npm run build

# ─── Étape 2 : Serve avec Nginx ──────────────────────────────────
FROM nginx:alpine

COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
```

### Configuration Nginx (SPA + proxy API)

```nginx
# frontend/nginx.conf
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    # Compression
    gzip on;
    gzip_types text/plain text/css application/json application/javascript;

    # Cache pour les assets buildés (hash dans le nom)
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff2)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # Proxy des appels API vers le backend Spring Boot
    location /api {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # React Router — toujours servir index.html
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

---

## docker-compose.yml (Production)

```yaml
version: '3.9'

services:

  postgres:
    image: postgres:16-alpine
    container_name: myapp-db
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${DB_NAME}
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER} -d ${DB_NAME}"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - app-network

  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    container_name: myapp-backend
    restart: unless-stopped
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${DB_NAME}
      SPRING_DATASOURCE_USERNAME: ${DB_USER}
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
    depends_on:
      postgres:
        condition: service_healthy
    networks:
      - app-network
    # Ne pas exposer 8080 en prod (nginx fait le proxy)

  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile
      args:
        VITE_API_BASE_URL: /api/v1    # Proxy nginx, pas d'URL absolue
    container_name: myapp-frontend
    restart: unless-stopped
    ports:
      - "80:80"
    depends_on:
      - backend
    networks:
      - app-network

volumes:
  postgres_data:

networks:
  app-network:
    driver: bridge
```

---

## docker-compose.dev.yml (Développement)

```yaml
# Lance avec : docker-compose -f docker-compose.yml -f docker-compose.dev.yml up
version: '3.9'

services:

  postgres:
    ports:
      - "5432:5432"    # Exposé pour accès depuis l'IDE/DBeaver

  backend:
    build:
      context: ./backend
      target: builder  # Arrêt à l'étape builder pour dev
    volumes:
      - ./backend:/app  # Hot reload avec Spring DevTools
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: dev
      SPRING_DEVTOOLS_RESTART_ENABLED: "true"

  frontend:
    build:
      context: ./frontend
      target: builder
    command: npm run dev -- --host 0.0.0.0
    volumes:
      - ./frontend/src:/app/src  # Hot Module Replacement
    ports:
      - "5173:5173"
    environment:
      VITE_API_BASE_URL: http://localhost:8080/api/v1
```

---

## Fichier .env

```bash
# .env — Ne jamais committer ce fichier !

# Base de données
DB_NAME=myapp
DB_USER=myapp_user
DB_PASSWORD=changeme_en_prod

# JWT
JWT_SECRET=votre_secret_jwt_tres_long_et_aleatoire_minimum_256_bits

# Optionnel
APP_DOMAIN=monapp.com
```

```bash
# .env.example — Committer ce fichier comme template
DB_NAME=myapp
DB_USER=myapp_user
DB_PASSWORD=
JWT_SECRET=
```

---

## Commandes utiles

```bash
# Démarrer en production
docker-compose up -d

# Démarrer en développement
docker-compose -f docker-compose.yml -f docker-compose.dev.yml up

# Rebuild après changement de code
docker-compose up -d --build backend

# Voir les logs
docker-compose logs -f backend

# Accéder au shell du conteneur
docker exec -it myapp-backend sh

# Arrêter et supprimer les volumes (reset BDD)
docker-compose down -v
```

---

## Checklist

- [ ] `.env` dans `.gitignore`
- [ ] `.env.example` commité avec les clés sans valeurs
- [ ] `healthcheck` sur le service postgres
- [ ] `depends_on` avec `condition: service_healthy`
- [ ] Image Spring Boot avec utilisateur non-root
- [ ] Multi-stage build pour des images légères
- [ ] Volumes nommés pour la persistance de la BDD
- [ ] Nginx configuré pour les routes SPA (`try_files`)
- [ ] Proxy Nginx pour `/api` → backend (pas de CORS en prod)

'@

Write-Host "  docker-compose-springboot OK" -ForegroundColor Yellow

Write-Host ""
Write-Host "4 skills installes avec succes dans : " -ForegroundColor Green
Write-Host "Relancez Claude Code pour les activer." -ForegroundColor Green
