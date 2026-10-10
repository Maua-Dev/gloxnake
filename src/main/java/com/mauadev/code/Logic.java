package com.mauadev.code;

import com.mauadev.code.entities.Coordinate;
import com.mauadev.code.entities.GameState;
import com.mauadev.code.entities.Snake;
import java.util.*;

/** Dijkstra + Flood Fill do protótipo, sem alterar as entidades oficiais. */
public class Logic {
    public static Map<String, String> info() {
        Map<String, String> info = new HashMap<>();
        info.put("apiversion", "1");
        info.put("author", "");
        info.put("color", "#8B0000");
        info.put("head", "tiger-king");
        info.put("tail", "hook");
        return info;
    }
    public static void start(GameState state) { }
    public static void end(GameState state) { }

    public static String getMove(GameState state) {
        if (state == null || state.getYou() == null || state.getBoard() == null)
            throw new IllegalArgumentException("Estado sem cobra ou tabuleiro");
        List<Snake> snakes = new ArrayList<>();
        snakes.add(state.getYou());
        if (state.getBoard().getSnakes() != null) for (Snake snake : state.getBoard().getSnakes()) {
            if (snake != state.getYou() && (snake.getId() == null
                    || !Objects.equals(snake.getId(), state.getYou().getId()))) snakes.add(snake);
        }
        int[] health = new int[snakes.size()];
        int[][][] bodies = new int[snakes.size()][][];
        for (int i = 0; i < snakes.size(); i++) {
            health[i] = snakes.get(i).getHealth();
            bodies[i] = coordinates(snakes.get(i).getBody());
            if (bodies[i].length == 0) {
                Coordinate head = snakes.get(i).getHead();
                if (head == null) throw new IllegalArgumentException("Cobra sem corpo/cabeça");
                bodies[i] = new int[][] {{head.getX(), head.getY()}};
            }
        }
        // Game.java só expõe id/timeout: a integração assume regras padrão,
        // tabuleiro sem wrap e hazardDamagePerTurn=14. O protótipo conserva
        // wrapped/dano configurável sem inventar getters nas entidades.
        return new Brain(state.getBoard().getWidth(), state.getBoard().getHeight(),
                false, Brain.DANO_HAZARD_PADRAO, health, bodies, 0,
                coordinates(state.getBoard().getFood()), coordinates(state.getBoard().getHazards())).decide();
    }
    private static int[][] coordinates(List<Coordinate> coordinates) {
        if (coordinates == null) return new int[0][];
        int[][] result = new int[coordinates.size()][2];
        for (int i = 0; i < result.length; i++) {
            result[i][0] = coordinates.get(i).getX();
            result[i][1] = coordinates.get(i).getY();
        }
        return result;
    }

    enum Disputa { NEUTRA, FAVORAVEL, EMPATADA, DESFAVORAVEL }
    /** Risco preservado mesmo quando todas as alternativas são ruins. */
    static final class Avaliacao {
        final int direcao, destino, vida, espaco, maiorRival, deficitEspaco;
        final boolean legal;
        final double pontuacao;
        final Disputa disputa;
        Avaliacao(int direcao, int destino, int vida, int espaco, int maiorRival,
                  int deficitEspaco, boolean legal, double pontuacao, Disputa disputa) {
            this.direcao = direcao; this.destino = destino; this.vida = vida;
            this.espaco = espaco; this.maiorRival = maiorRival; this.deficitEspaco = deficitEspaco;
            this.legal = legal; this.pontuacao = pontuacao; this.disputa = disputa;
        }
        boolean perigosa() { return disputa == Disputa.EMPATADA || disputa == Disputa.DESFAVORAVEL; }
    }

    // BEGIN SHARED BRAIN: sincronizado com tools/SnakeBrain.java.
    static final class Brain {
        static final String[] MOVES = {"up", "down", "left", "right"};
        static final int[] DX = {0, 0, -1, 1}, DY = {1, -1, 0, 0};
        static final int INF = Integer.MAX_VALUE, CUSTO_HAZARD = 5, DANO_HAZARD_PADRAO = 14;
        static final int VIDA_MAXIMA = 100, LIMIAR_FOME = 35, MULTIPLO_ESPACO_UTIL = 3;
        static final double PESO_ESPACO = 4, PESO_SAIDAS = 2;
        static final double PESO_COMIDA = 80, PESO_COMIDA_FOME = 300, FATOR_COMIDA_DISPUTADA = 0.25;
        static final double PENALIDADE_HAZARD = 12, PESO_ATAQUE = 18, BONUS_CAMINHO_CAUDA = 6;
        final int W, H, V, me, hazardDamage;
        final boolean wrapped;
        final int[] health;
        final int[][][] bodies;
        final boolean[] bloq, hazard, temFood;
        final int[] maiorCabecaRival, quantidadeHazards;
        final double[] bonusAtaque;
        final int[][] grafo, grafoTurnos, distRivais;

        Brain(int width, int height, boolean wrapped, int hazardDamage, int[] health,
              int[][][] bodies, int me, int[][] food, int[][] hazards) {
            if (width <= 0 || height <= 0 || me < 0 || me >= bodies.length
                    || bodies.length != health.length || hazardDamage < 0)
                throw new IllegalArgumentException("Dimensões/cobras/dano inválidos");
            W = width; H = height; V = Math.multiplyExact(W, H);
            this.wrapped = wrapped; this.hazardDamage = hazardDamage;
            this.health = health; this.bodies = bodies; this.me = me;
            bloq = new boolean[V]; hazard = new boolean[V]; temFood = new boolean[V];
            maiorCabecaRival = new int[V]; quantidadeHazards = new int[V]; bonusAtaque = new double[V];
            grafo = new int[V][V]; grafoTurnos = new int[V][V]; distRivais = new int[bodies.length][];
            for (int[] f : food) if (dentro(f)) temFood[cell(f)] = true;
            for (int[] z : hazards) if (dentro(z)) { hazard[cell(z)] = true; quantidadeHazards[cell(z)]++; }
            marcaObstaculos(); montaGrafo(); marcaCabecasRivais();
        }
        boolean dentro(int[] p) { return p[0] >= 0 && p[0] < W && p[1] >= 0 && p[1] < H; }
        int cell(int[] p) { return p[1] * W + p[0]; }
        int nb(int c, int d) {
            int x = c % W + DX[d], y = c / W + DY[d];
            if (wrapped) { x = Math.floorMod(x, W); y = Math.floorMod(y, H); }
            else if (x < 0 || y < 0 || x >= W || y >= H) return -1;
            return y * W + x;
        }
        void marcaObstaculos() {
            for (int[][] body : bodies) {
                if (body.length == 0) throw new IllegalArgumentException("Corpo vazio");
                // Sai a cauda ANTIGA antes da comida. Crescer duplica a NOVA.
                // Caudas duplicadas continuam bloqueadas pela ocorrência anterior.
                for (int k = 0; k < body.length; k++) {
                    // Os testes oficiais incluem uma cauda fora da borda que
                    // desaparece neste turno; ela não é um obstáculo ativo.
                    if (k == body.length - 1 && !dentro(body[k])) continue;
                    if (!dentro(body[k])) throw new IllegalArgumentException("Corpo fora do tabuleiro");
                    if (k < body.length - 1) bloq[cell(body[k])] = true;
                }
            }
        }
        int vidaDepois(int snake, int destination) {
            if (temFood[destination]) return VIDA_MAXIMA;
            long damage = 1L + (long) hazardDamage * quantidadeHazards[destination];
            return (int) Math.max(Integer.MIN_VALUE, health[snake] - damage);
        }
        boolean movimentoLegal(int snake, int destination) {
            if (destination < 0 || bloq[destination] || vidaDepois(snake, destination) <= 0) return false;
            // Nossa cobra evita reversão em tamanho 2; o rival pode fazê-la
            // legitimamente, pois nesse caso pescoço = cauda que sai do lugar.
            return snake != me || bodies[snake].length < 2 || destination != cell(bodies[snake][1]);
        }
        /** Só obstáculos físicos; risco imediato nunca vira parede permanente. */
        void montaGrafo() {
            for (int a = 0; a < V; a++) for (int d = 0; d < 4; d++) {
                int b = nb(a, d);
                if (b < 0 || b == a || bloq[b]) continue;
                grafo[a][b] = hazard[b] && !temFood[b] ? 1 + (CUSTO_HAZARD - 1) * quantidadeHazards[b] : 1;
                grafoTurnos[a][b] = 1;
            }
        }
        void marcaCabecasRivais() {
            for (int j = 0; j < bodies.length; j++) if (j != me) {
                int head = cell(bodies[j][0]), options = 0;
                boolean[] allowed = new boolean[V];
                for (int d = 0; d < 4; d++) {
                    int c = nb(head, d);
                    if (movimentoLegal(j, c) && !allowed[c]) { allowed[c] = true; options++; }
                }
                for (int c = 0; c < V; c++) if (allowed[c]) {
                    int growth = temFood[c] ? 1 : 0;
                    maiorCabecaRival[c] = Math.max(maiorCabecaRival[c], bodies[j].length + growth);
                    if (bodies[j].length < bodies[me].length) bonusAtaque[c] += PESO_ATAQUE / options;
                }
                // Dijkstra com custo unitário mede TURNOS para a disputa de comida.
                distRivais[j] = dijkstra(grafoTurnos, head, new int[V], V, allowed);
            }
        }
        Disputa disputa(int destination) {
            int rival = maiorCabecaRival[destination];
            int myLength = bodies[me].length + (temFood[destination] ? 1 : 0);
            if (rival == 0) return Disputa.NEUTRA;
            if (rival < myLength) return Disputa.FAVORAVEL;
            return rival == myLength ? Disputa.EMPATADA : Disputa.DESFAVORAVEL;
        }
        // Dijkstra original: matriz, distâncias, visitados e pai preservados.
        static int distanciaMinima(int[] dist, boolean[] visitado, int V) {
            int min = INF, index = -1;
            for (int v = 0; v < V; v++) if (!visitado[v] && dist[v] < min) { min = dist[v]; index = v; }
            return index;
        }
        static int[] dijkstra(int[][] grafo, int src, int[] pai, int V) {
            return dijkstra(grafo, src, pai, V, null);
        }
        static int[] dijkstra(int[][] grafo, int src, int[] pai, int V, boolean[] firstAllowed) {
            int[] dist = new int[V]; boolean[] visitado = new boolean[V];
            Arrays.fill(dist, INF); Arrays.fill(pai, -1); dist[src] = 0;
            for (int count = 0; count < V; count++) {
                int u = distanciaMinima(dist, visitado, V);
                if (u == -1) break;
                visitado[u] = true;
                for (int v = 0; v < V; v++) {
                    int cost = grafo[u][v];
                    if (visitado[v] || cost <= 0 || (u == src && firstAllowed != null && !firstAllowed[v])) continue;
                    long distance = (long) dist[u] + cost;
                    if (distance < dist[v]) { dist[v] = (int) distance; pai[v] = u; }
                }
            }
            return dist;
        }
        int primeiroPasso(int[] pai, int head, int alvo) {
            if (head == alvo) return -1;
            int c = alvo;
            for (int i = 0; i < V && c != -1; i++) {
                if (pai[c] == head) return c;
                c = pai[c];
            }
            return -1;
        }
        /** Flood Fill físico; hazards continuam transitáveis. */
        int espaco(int start) {
            boolean[] visited = new boolean[V]; ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start); visited[start] = true; int count = 0;
            while (!queue.isEmpty()) {
                int c = queue.remove(); count++;
                for (int d = 0; d < 4; d++) {
                    int b = nb(c, d);
                    if (b >= 0 && !visited[b] && !bloq[b]) { visited[b] = true; queue.add(b); }
                }
            }
            return count;
        }
        /** Confere saúde ao percorrer o caminho reconstruído e conta turnos reais. */
        int turnosAteComida(int start, int target, int[] pai, int firstHealth) {
            ArrayDeque<Integer> path = new ArrayDeque<>();
            int c = target;
            for (int guard = 0; c != start; guard++) {
                if (c < 0 || guard >= V) return INF;
                path.push(c); c = pai[c];
            }
            int life = firstHealth, steps = 1;
            while (!path.isEmpty()) {
                c = path.pop(); steps++;
                long damage = 1L + (long) hazardDamage * quantidadeHazards[c];
                life = temFood[c] ? VIDA_MAXIMA : (int) Math.max(Integer.MIN_VALUE, life - damage);
                if (life <= 0) return INF;
            }
            return steps;
        }
        Avaliacao avaliar(int direction) {
            int destination = nb(cell(bodies[me][0]), direction);
            if (!movimentoLegal(me, destination)) return new Avaliacao(direction, destination,
                    destination < 0 ? 0 : vidaDepois(me, destination), 0,
                    destination < 0 ? 0 : maiorCabecaRival[destination], bodies[me].length,
                    false, Double.NEGATIVE_INFINITY, Disputa.NEUTRA);
            int life = vidaDepois(me, destination);
            int newLength = bodies[me].length + (temFood[destination] ? 1 : 0);
            int space = espaco(destination), exits = 0;
            for (int d = 0; d < 4; d++) { int c = nb(destination, d); if (c >= 0 && !bloq[c]) exits++; }
            double score = PESO_ESPACO * Math.min(space, MULTIPLO_ESPACO_UTIL * newLength) + PESO_SAIDAS * exits;
            if (hazard[destination] && !temFood[destination]) score -= PENALIDADE_HAZARD * quantidadeHazards[destination];
            Disputa risk = disputa(destination);
            if (risk == Disputa.FAVORAVEL) score += Math.min(PESO_ATAQUE, bonusAtaque[destination]);
            // Cada primeira jogada recebe seu próprio caminho. Uma rota cujo
            // primeiro passo é perigoso não esconde a alternativa segura.
            int[] pai = new int[V]; int[] distances = dijkstra(grafo, destination, pai, V);
            double foodScore = 0;
            for (int f = 0; f < V; f++) if (temFood[f] && distances[f] != INF) {
                int arrival = turnosAteComida(destination, f, pai, life);
                if (arrival == INF) continue;
                boolean contested = false;
                for (int j = 0; j < bodies.length; j++) if (j != me) {
                    int rivalArrival = distRivais[j][f];
                    if (rivalArrival < arrival || (rivalArrival == arrival && bodies[j].length >= bodies[me].length)) contested = true;
                }
                double weight = health[me] <= LIMIAR_FOME ? PESO_COMIDA_FOME : PESO_COMIDA;
                if (contested) weight *= FATOR_COMIDA_DISPUTADA;
                int cost = grafo[cell(bodies[me][0])][destination] + distances[f];
                foodScore = Math.max(foodScore, weight / (cost + 1.0));
            }
            score += foodScore;
            int[] last = bodies[me][bodies[me].length - 1];
            int tail = dentro(last) ? cell(last) : -1;
            if (tail >= 0 && !bloq[tail] && distances[tail] != INF) score += BONUS_CAMINHO_CAUDA / (distances[tail] + 1.0);
            return new Avaliacao(direction, destination, life, space, maiorCabecaRival[destination],
                    Math.max(0, newLength - space), true, score, risk);
        }
        boolean melhor(Avaliacao a, Avaliacao b) {
            if (a.perigosa() != b.perigosa()) return !a.perigosa();
            if (a.perigosa()) {
                int myA = bodies[me].length + (temFood[a.destino] ? 1 : 0);
                int myB = bodies[me].length + (temFood[b.destino] ? 1 : 0);
                int gapA = a.maiorRival - myA, gapB = b.maiorRival - myB;
                if (gapA != gapB) return gapA < gapB;
            }
            if (a.deficitEspaco != b.deficitEspaco) return a.deficitEspaco < b.deficitEspaco;
            return a.pontuacao > b.pontuacao;
        }
        String decide() {
            Avaliacao best = null;
            for (int d = 0; d < 4; d++) {
                Avaliacao candidate = avaliar(d);
                if (candidate.legal && (best == null || melhor(candidate, best))) best = candidate;
            }
            return best == null ? "up" : MOVES[best.direcao];
        }
    }
    // END SHARED BRAIN
}
