# Catálogo de diferenças dos forks do Tachiyomi — funcionalidades de mangá

**Pesquisa:** 28 de outubro de 2026  
**Base de comparação:** Tachiyomi original, encerrado em janeiro de 2024.  
**Objetivo:** reunir funcionalidades documentadas em forks/continuações que possam servir de referência para futuras melhorias em um leitor de mangás, principalmente com **arquivos locais** e, quando disponível, **fontes online**.

## Como ler este relatório

- Cada item significa **“encontrei no [fork] uma funcionalidade que faz [X]”**; não significa que só esse fork a possua.
- **[Documentado]**: o recurso aparece no README, na documentação ou no changelog do repositório indicado. Não significa que eu tenha instalado e testado o APK.
- **[Também presente em outros]**: recurso compartilhado, herdado ou posteriormente incorporado por outros projetos. Ele continua listado como ideia.
- **Não incluí como novidade** recursos que já eram essenciais ao Tachiyomi original: leitura local genérica, CBZ/ZIP/CBR/RAR/EPUB genéricos, leitura online via extensões, categorias simples, temas claro/escuro, trackers básicos, cópias de segurança e múltiplas direções de leitura.
- **Foco:** organização de títulos/volumes/capítulos, leitura de páginas, metadados, pesquisa, descobertas, downloads, sincronização e integração de acervos. Não listei mudanças de branding, atualizações Android sem efeito direto no mangá, nem funções centradas em vídeo/romances.
- **Importante:** o Mihon evoluiu depois de 2024. Por isso uma função pode ser diferente do *Tachiyomi original*, mas **não ser exclusiva em outubro de 2026**. Na ausência de uma auditoria completa do código de todos os forks, as distinções são documentais, e não uma prova de exclusividade.

---

## 1. Tankobon — volumes físicos e biblioteca somente local

**Código:** https://github.com/mednasserallah/tankobon  
**Fontes:** [README](https://github.com/mednasserallah/tankobon), [formato da biblioteca](https://github.com/mednasserallah/tankobon/blob/master/docs/LOCAL_LIBRARY_FORMAT.md), [capas](https://github.com/mednasserallah/tankobon/blob/master/docs/COVERS.md), [OCR e tradução](https://github.com/mednasserallah/tankobon/blob/master/docs/TEXT_DETECTION.md), [arquitetura](https://github.com/mednasserallah/tankobon/blob/master/docs/ARCHITECTURE.md).  
**Limitação relevante:** não usa fontes online nem extensões. Tem como unidade o **volume**, não o capítulo.

- **TB-01 — [Documentado] Volume em vez de capítulo:** achei no **Tankobon** uma biblioteca em que cada arquivo `.cbz`/arquivo compactado/pasta representa um **volume encadernado**; a lista de leitura usa volumes em lugar de capítulos.
- **TB-02 — [Documentado] Número do volume pelo nome:** achei um interpretador de nomes como `Nome - Volume 03 (2016).cbz`, que identifica automaticamente o número do volume.
- **TB-03 — [Documentado] Identificação de edições:** achei identificação de edições pelo nome da pasta, como `BLAME! (Master Edition)`, com indicação da edição na ficha do título.
- **TB-04 — [Documentado] Volumes omnibus:** achei suporte a nomes como `Volume 01-02`, reconhecendo que um arquivo reúne os volumes 1 e 2.
- **TB-05 — [Documentado] Ano por volume:** achei extração do ano que aparece entre parênteses no nome de cada volume, sem presumir que todos compartilham o mesmo ano.
- **TB-06 — [Documentado] One-shots:** achei reconhecimento de obras de volume único sem obrigar o arquivo a ter `Volume NN` no nome.
- **TB-07 — [Documentado] Lacunas na coleção:** achei avisos quando faltam números na sequência, por exemplo “faltam 5 volumes”; isso evita confundir coleção incompleta com biblioteca completa.
- **TB-08 — [Documentado] Capas específicas por volume:** achei uma grade em que **cada volume mostra sua própria capa**, obtida da primeira imagem ordenada dentro do arquivo, além da capa geral da série.
- **TB-09 — [Documentado] Atualização de miniaturas após substituir arquivos:** achei miniaturas de volumes com cache invalidado ao mudar a data de modificação do arquivo; útil para acervos locais editados manualmente.
- **TB-10 — [Documentado] OCR na página:** achei um comando no leitor que detecta texto em inglês na imagem e o mostra em uma lista na ordem de leitura.
- **TB-11 — [Documentado] Corrigir e copiar o texto detectado:** achei a opção de editar manualmente um erro do OCR e copiar o resultado, sem modificar a imagem original.
- **TB-12 — [Documentado] Tradução de falas:** achei tradução de inglês para árabe linha por linha ou de todas as linhas detectadas; há alternativa offline com modelos locais e opção online via DeepL.
- **TB-13 — [Documentado] OCR respeitando a leitura de mangá:** achei ordenação de texto levando em conta direita→esquerda e reconhecimento focado na área visível quando a página está ampliada.

**Ideias especialmente úteis para acervo local:** TB-01, TB-03, TB-04, TB-07, TB-08 e TB-10.

**Cuidado:** não contei suporte comum a CBZ/CBR/EPUB como novidade do Tankobon; esse tipo de leitura já existia no Tachiyomi. Os formatos adicionais citados na documentação do Tankobon (por exemplo, `.7z`/`.cb7`/`.tar`) merecem testes específicos antes de serem considerados uma melhoria exclusiva.

---

## 2. Komikku — descobertas, biblioteca, sincronização e manutenção

**Código:** https://github.com/komikku-app/komikku  
**Fonte:** [README e relação de funcionalidades próprias](https://github.com/komikku-app/komikku/blob/master/README.md).  
**Fontes de mangás:** locais e online.

- **KM-01 — [Documentado] Relacionados da própria fonte:** achei no **Komikku** sugestões de obras relacionadas extraídas do próprio site-fonte do mangá, não apenas de serviços de rastreamento externos.
- **KM-02 — [Documentado] Categorias ocultas:** achei categorias que podem ficar escondidas na biblioteca para reduzir a exposição de coleções específicas.
- **KM-03 — [Documentado] Tema por capa do título:** achei cores da tela de detalhes e do leitor ajustadas automaticamente conforme a capa da obra.
- **KM-04 — [Documentado] Favoritar em lote:** achei a possibilidade de adicionar múltiplos mangás à biblioteca de uma só vez.
- **KM-05 — [Documentado] Origem e idioma à vista:** achei ícones de fonte e idioma exibidos na biblioteca e em outras telas para diferenciar edições e origens de obras.
- **KM-06 — [Documentado] Feed multifuentes:** achei um feed de obras de várias fontes online reunidas, com mais resultados por fonte em relação à implementação usada como base.
- **KM-07 — [Documentado] Atualizações agrupadas:** achei agrupamento de entradas na aba de atualizações para diminuir a fragmentação por capítulo.
- **KM-08 — [Documentado] Capa na notificação:** achei notificações de atualização que exibem a capa da obra.
- **KM-09 — [Documentado] Sincronização bidirecional automática:** achei sincronização automática de progresso **em ambas as direções** com serviços de acompanhamento, conforme o suporte do tracker.
- **KM-10 — [Documentado] Pesquisas salvas em atalhos:** achei filtros ou buscas previamente salvos mostrados como chips ao navegar por uma fonte.
- **KM-11 — [Documentado] Capa panorâmica:** achei visualização expandida de capas muito largas, sem tratá-las apenas como pequenas miniaturas verticais.
- **KM-12 — [Documentado] Mesclar várias obras de uma só vez:** achei a junção simultânea de múltiplas entradas de biblioteca referentes à mesma obra.
- **KM-13 — [Documentado] Seleção por intervalo na migração:** achei seleção de uma sequência de mangás para migrar de fonte sem marcá-los um a um.
- **KM-14 — [Documentado] Erros persistentes de atualização:** achei uma tela que conserva a lista de obras cujo processo de atualização falhou, permitindo rever os erros depois.
- **KM-15 — [Documentado] Migrar mangás que falharam:** achei um fluxo que usa a lista de falhas de atualização para migrar essas entradas a outras fontes.
- **KM-16 — [Documentado] Prévia da atualização inteligente:** achei uma tela mostrando **quais mangás serão verificados** no próximo processo de atualização inteligente da biblioteca.
- **KM-17 — [Documentado] Filtrar fontes por texto e conteúdo:** achei busca por nome da fonte e filtro rápido de fontes com conteúdo adulto nas telas de extensões, navegação e migração.
- **KM-18 — [Documentado] Guardar a configuração do feed:** achei backup, restauração, sincronização e reordenação do feed de fontes/buscas.
- **KM-19 — [Documentado] Adicionar/remover mantendo pressionado:** achei uma ação de toque longo para incluir ou retirar rapidamente uma obra da biblioteca em diversas telas.
- **KM-20 — [Documentado] Intervalo de revarredura de arquivos:** achei configuração de frequência para atualizar as entradas baseadas em armazenamento baixado/local.
- **KM-21 — [Documentado] Botão de retomar leitura reposicionável:** achei a possibilidade de encaixar o botão de ler/continuar à esquerda ou à direita.
- **KM-22 — [Documentado] Progresso visível das tarefas:** achei faixas de progresso durante sincronização, restauração de backup e atualização da biblioteca.

**Nota de origem:** o Komikku também herda muitas funções do TachiyomiSY. Para não duplicar ideias, elas aparecem prioritariamente na seção do SY.

---

## 3. TachiyomiSY — metadados, mesclagem, busca e leitura

**Código:** https://github.com/jobobby04/TachiyomiSY  
**Fonte:** [README](https://github.com/jobobby04/TachiyomiSY/blob/master/README.md).  
**Fontes de mangás:** locais e online.

- **SY-01 — [Documentado] Reconhecer webtoon automaticamente:** achei no **TachiyomiSY** detecção de webtoons com troca automática do modo de leitura para rolagem vertical.
- **SY-02 — [Documentado] Recomendações externas:** achei recomendações de mangás usando MyAnimeList e AniList; em obras MangaDex há integração de obras semelhantes.
- **SY-03 — [Documentado] Ocultar conteúdo adulto por filtro:** achei um filtro de biblioteca para esconder temporariamente mangás identificados como adultos.
- **SY-04 — [Documentado] Filtrar por tracker:** achei filtragem de obras rastreadas versus não rastreadas e por status de acompanhamento.
- **SY-05 — [Documentado] Grupos personalizados de fontes:** achei categorias próprias para agrupar fontes online, além da lista de fontes fixadas.
- **SY-06 — [Documentado] Editar informações de um mangá:** achei edição de metadados da obra dentro do leitor, em vez de aceitar somente o que veio da fonte.
- **SY-07 — [Documentado] Salvar ou compartilhar capa:** achei ações para visualizar, exportar e compartilhar a capa cadastrada de um mangá.
- **SY-08 — [Documentado] Categorias dinâmicas:** achei grupos de biblioteca calculados automaticamente a partir de propriedades dos títulos, em vez de depender apenas de pastas/categorias manuais.
- **SY-09 — [Documentado] Fundo adaptativo da página:** achei ajuste do fundo do leitor conforme a cor da página, especialmente útil em leituras lateral e vertical.
- **SY-10 — [Documentado] Bloquear zoom no modo webtoon:** achei uma opção para evitar ampliação indesejada em leitura por rolagem.
- **SY-11 — [Documentado] Limpeza rápida de nomes:** achei um comando que simplifica títulos de mangás com nomes poluídos ou sufixos indesejados.
- **SY-12 — [Documentado] Salvar pesquisas:** achei possibilidade de registrar consultas para reutilização posterior em catálogos.
- **SY-13 — [Documentado] Rolagem automática:** achei deslocamento automático das páginas durante a leitura.
- **SY-14 — [Documentado] Ajustar pré-carregamento:** achei controle da quantidade de páginas preparadas antecipadamente para leitura.
- **SY-15 — [Documentado] Ajustar cache de imagens:** achei controle de tamanho do cache para equilibrar desempenho e uso de espaço.
- **SY-16 — [Documentado] Tocar em tag para pesquisar:** achei pesquisa iniciada pelo toque em uma tag da obra: toque simples para busca local; toque longo para busca global.
- **SY-17 — [Documentado] Unir a mesma obra de fontes diferentes:** achei uma entrada de biblioteca capaz de reunir capítulos de várias fontes que apontam para o mesmo mangá.
- **SY-18 — [Documentado] Reordenação manual por arrastar:** achei ordenação de mangás da biblioteca por arrastar e soltar.
- **SY-19 — [Documentado] Pesquisa avançada:** achei pesquisa com exclusões e correspondência exata entre aspas, além de outros operadores.
- **SY-20 — [Documentado] Feed de lançamentos selecionados:** achei uma aba que permite consultar rapidamente mangás recentes de até cinco fontes escolhidas.
- **SY-21 — [Documentado] Recursos específicos de LANraragi:** achei integração aprimorada com a fonte LANraragi, relevante para coleções autogerenciadas.

**Ressalva:** parte dessas funções também existe no Komikku, que deriva do SY, e algumas foram incorporadas posteriormente ao Mihon. A autoria e a exclusividade atuais não foram verificadas em nível de commit.

---

## 4. TachiyomiJ2K — experiência de biblioteca e leitor

**Código:** https://github.com/Jays2Kings/tachiyomiJ2K  
**Fonte:** [README](https://github.com/Jays2Kings/tachiyomiJ2K/blob/master/README.md).  
**Fontes de mangás:** locais e online.

- **J2K-01 — [Documentado] Duas páginas em uma tela:** achei no **TachiyomiJ2K** combinação de duas páginas de mangá para leitura em tablets ou telas largas.
- **J2K-02 — [Documentado] Ficha visual definida pela capa:** achei a tela de detalhes do mangá com aparência adaptada às cores e à imagem da sua capa.
- **J2K-03 — [Documentado] Barra de ferramentas para uma mão:** achei barra de leitura expandida e posicionada para facilitar alcance com o polegar.
- **J2K-04 — [Documentado] Pesquisa flutuante:** achei campo de busca flutuante na biblioteca e durante a navegação de fontes.
- **J2K-05 — [Documentado] Biblioteca vertical recolhível:** achei categorias exibidas numa lista vertical única, podendo expandir ou recolher cada grupo.
- **J2K-06 — [Documentado] Grade escalonada:** achei uma grade de capas com alturas/posições escalonadas, não limitada à grade rígida tradicional.
- **J2K-07 — [Documentado] Filtros dinâmicos de agrupamento:** achei grupos automáticos por tags, status de tracker, origem e outros critérios.
- **J2K-08 — [Documentado] Tela de recentes reunificada:** achei uma página com obras adicionadas recentemente, capítulos novos e séries cuja leitura pode ser retomada.
- **J2K-09 — [Documentado] Estatísticas de leitura:** achei uma página dedicada a estatísticas da coleção/leitura.
- **J2K-10 — [Documentado] Atalho de continuar no Android:** achei atalho na tela inicial para abrir o capítulo recente do mangá lido anteriormente.
- **J2K-11 — [Documentado] Exclusão de capítulos com desfazer:** achei um fluxo de remoção do mangá que também remove capítulos baixados e permite desfazer imediatamente.
- **J2K-12 — [Documentado] Lista de capítulos dentro do leitor:** achei a possibilidade de consultar todos os capítulos sem abandonar a visualização de páginas.
- **J2K-13 — [Documentado] Migração automática em lote:** achei migração múltipla entre fontes, creditada no README como funcionalidade herdada do TachiyomiEH.

**Nota:** categorização dinâmica e migração em lote também existem em outros forks/Mihon moderno. Aqui interessa sobretudo a **implementação visual do J2K**.

---

## 5. Yōkai — metadados de capítulos locais

**Código:** https://github.com/null2264/yokai  
**Fonte:** [README](https://github.com/null2264/yokai/blob/master/README.md).  
**Fontes de mangás:** locais e online.

- **YK-01 — [Documentado; também presente no Mihon moderno] Ler `ComicInfo.xml` de capítulos locais:** achei no **Yōkai** a leitura dos campos **título, número e grupo de tradução (`scanlator`)** a partir de `ComicInfo.xml` contido no capítulo local. Assim, a exibição não precisa depender apenas do nome do arquivo.

**Sobre a exclusividade:** o README do Yōkai destaca essa mudança em relação ao seu upstream na época. O [Mihon 0.19.1 (2025)](https://github.com/mihonapp/mihon/blob/main/CHANGELOG.md) posteriormente documentou suporte equivalente. Não seria correto chamar esse recurso de exclusivo do Yōkai em 2026.

**Nota:** Yōkai herda as principais mudanças de interface do J2K e o filtro de conteúdo do SY. Não as repeti aqui como se fossem criações próprias.

---

## 6. Mihon — continuidade com melhorias posteriores a janeiro de 2024

**Código:** https://github.com/mihonapp/mihon  
**Fontes:** [CHANGELOG oficial](https://github.com/mihonapp/mihon/blob/main/CHANGELOG.md), [guia de fonte local](https://mihon.app/docs/guides/local-source/).  
**Fontes de mangás:** locais e online.

- **MH-01 — [Documentado] Notas pessoais por mangá:** achei no **Mihon** a possibilidade de guardar observações próprias associadas à obra (adicionado no changelog v0.19.0, de 2025).
- **MH-02 — [Documentado] Descrições formatadas:** achei interpretação de Markdown nas descrições de títulos, permitindo formatação além de texto simples (v0.19.0).
- **MH-03 — [Documentado] Migração em massa no núcleo principal:** achei migração de vários mangás entre fontes na aplicação sucessora, documentada na v0.19.0.
- **MH-04 — [Documentado] Busca lógica avançada:** achei na biblioteca operadores `&&`, `||`, `-`, parênteses, comparações e buscas por campos (`title:`, `tag:`, `source:`, `notes:` e outros); melhoria documentada na v0.20.2.
- **MH-05 — [Documentado] Filtrar especificamente a fonte local:** achei pesquisa `src:local`, permitindo localizar os mangás de arquivos próprios em meio a uma biblioteca mista (v0.19.4).
- **MH-06 — [Documentado] Filtrar a aba de atualizações por categoria:** achei filtro por categoria na lista de lançamentos (v0.20.3).
- **MH-07 — [Documentado] Selecionar capítulos marcados para download:** achei inclusão de capítulos marcados com bookmark entre as opções de seleção de downloads (v0.19.4).
- **MH-08 — [Documentado] Datas em `ComicInfo.xml`:** achei leitura de ano, mês e dia de capítulos locais a partir dos metadados do arquivo (v0.21.0, de 2026).
- **MH-09 — [Documentado] Exportar datas ao baixar:** achei gravação de datas de capítulos no `ComicInfo.xml` ao salvar downloads (v0.21.0).
- **MH-10 — [Documentado] Retomar download de imagem:** achei suporte a retomada de download de páginas *quando a fonte suporta*, sem reiniciar do zero (v0.20.2).
- **MH-11 — [Documentado] Pré-carregar só no Wi-Fi:** achei opção de carregar adiante páginas da leitura apenas quando conectado a Wi-Fi (v0.21.0).
- **MH-12 — [Documentado] Identificar duplicações com trackers:** achei utilização de dados de serviços de acompanhamento para melhorar a detecção de obras duplicadas na biblioteca (v0.19.4).
- **MH-13 — [Documentado] Ajustar concorrência de downloads:** achei configuração da quantidade de downloads simultâneos por fonte e de páginas (v0.19.2).
- **MH-14 — [Documentado] Navegação vertical entre capítulos:** achei um navegador de capítulos vertical para o modo de tiras longas, com ajustes de altura e de leitores em que ele aparece (v0.20.x).

**Não confundir:** funcionalidades em `Unreleased` do changelog (por exemplo, renderizador experimental de imagens de maior qualidade) **não foram tratadas como lançadas** nesta relação.

---

## 7. TachiyomiEH — importação e organização por galerias

**Código:** https://github.com/NerdNumber9/TachiyomiEH  
**Fonte:** [README](https://github.com/NerdNumber9/TachiyomiEH).  
**Fontes de mangás:** locais e online; especialização em galerias adultas. **Projeto antigo/descontinuado**.

- **EH-01 — [Documentado] Favoritos sincronizados com uma fonte:** achei no **TachiyomiEH** sincronização da lista de favoritos com a conta E-Hentai/ExHentai.
- **EH-02 — [Documentado] Pesquisar favoritos por propriedades:** achei filtros de favoritos online por tag, idioma e artista.
- **EH-03 — [Documentado] Importar muitos links de uma vez:** achei importação em lote de URLs de galerias; também permite importar URL individual colando-a na pesquisa do catálogo.
- **EH-04 — [Documentado] Baixador acelerado no leitor:** achei um mecanismo de download anunciado como mais rápido ao obter imagens da leitura; o README não fornece uma medida que permita quantificar o ganho.

**Aplicabilidade:** EH-02 e EH-03 são ideias gerais para acervos de mangá, embora a implementação citada seja focada em fontes adultas. Não incluí o bloqueio de aplicativo por PIN, por ser uma função de segurança geral, e não propriamente de leitura de mangás.

---

## 8. Kahon — velocidade e controle de fontes

**Código:** https://github.com/AmanoTeam/Kahon  
**Fonte:** [README](https://github.com/AmanoTeam/Kahon).  
**Fontes de mangás:** locais e online.

- **KH-01 — [Documentado; com risco] Ignorar limites de requisição das extensões:** achei no **Kahon** a opção de não respeitar limites de taxa definidos pelas extensões para navegação, carregamento de páginas e downloads. **Não é recomendação automática:** pode causar bloqueios, sobrecarga ou violar condições da fonte.
- **KH-02 — [Documentado] Mais downloads simultâneos:** achei aumento do limite máximo de downloads concorrentes. A concorrência configurável também existe no Mihon moderno; o diferencial do Kahon é o relaxamento do teto.
- **KH-03 — [Documentado] Editar metadados localmente:** achei edição de dados do mangá na interface. **Também documentada no TachiyomiSY**; não exclusiva.
- **KH-04 — [Documentado] Atualização sem cache:** achei desativação de cache durante a atualização da biblioteca, para consultar dados novos diretamente da fonte. Pode elevar tráfego e tempo de resposta.

**Excluído deliberadamente:** “confiar automaticamente em todas as extensões” não é melhoria de leitura; reduz uma barreira de segurança e não deve ser copiada sem avaliação.

---

## 9. Rokku — confiabilidade no download e ergonomia

**Código:** https://github.com/rokku-app/rokku  
**Fonte:** [README](https://github.com/rokku-app/rokku).  
**Fontes de mangás:** locais e online.

- **RK-01 — [Documentado; também presente no Mihon moderno] Retomar páginas interrompidas:** achei no **Rokku** recuperação de páginas cujo download foi interrompido, em vez de reiniciar completamente a operação. O Mihon também documenta retomada de imagens quando a fonte é compatível.
- **RK-02 — [Documentado] Botão de detalhes que reage à rolagem:** achei ajuste do botão flutuante na ficha do mangá, que encolhe ou expande ao rolar para evitar encobrir a lista de capítulos.
- **RK-03 — [Documentado; herdado do Komikku] Lista persistente de obras com erro:** achei uma tela de atualização com falhas consultáveis depois de concluído o processo; o README atribui o recurso ao Komikku.
- **RK-04 — [Documentado; herdado do Komikku] Migração em lote de falhas:** achei migração de múltiplas obras com erros de atualização diretamente da lista; também atribuída ao Komikku.
- **RK-05 — [Documentado; herdado do J2K] Barra vertical de progresso para webtoons:** achei um controle de navegação vertical para leitura de tiras longas; o README associa essa família de recursos ao J2K.
- **RK-06 — [Documentado; herdado do J2K] Copiar/colar capas:** achei um menu de pressão prolongada para copiar ou colar capas de mangás. O README o apresenta entre os recursos herdados do J2K.

**Nota:** o Rokku deriva do Yōkai, que deriva do J2K. Grande parte de sua interface e funções não foi criada pelo Rokku.

---

## 10. Kikyo — downloads paralelos e pré-carregamento

**Código:** https://github.com/achyutbadyal/kikyo  
**Fonte:** [README](https://github.com/achyutbadyal/kikyo).  
**Fontes de mangás:** locais e online.

- **KK-01 — [Documentado] Downloads de capítulos em paralelo por fonte:** achei no **Kikyo** descarga simultânea de capítulos dentro de uma mesma fonte, em lugar de estritamente sequencial.
- **KK-02 — [Documentado] Pré-carregamento configurável:** achei parâmetros para ajustar pré-carregamento de páginas **e capítulos**, podendo antecipar a leitura seguinte.
- **KK-03 — [Documentado] Lista de capítulos durante a leitura:** achei acesso à lista de capítulos a partir do leitor, sem voltar à ficha do mangá. **Também aparece no J2K**; não exclusiva.

**Nota:** downloads simultâneos e pré-carregamento existem, sob formas diferentes, em outros projetos. O interesse do Kikyo é a implementação centrada nessas três melhorias.

---

## Índice de ideias por objetivo

| Quero melhorar... | Recursos úteis para estudar |
|---|---|
| Biblioteca de mangás locais | TB-01 a TB-09; KM-20; YK-01; MH-05; MH-08/09 |
| Metadados, tags e organização | TB-02 a TB-07; SY-06/08/11/16/17/19; KM-02/05/12; MH-01/04/08 |
| Interface da biblioteca | KM-04/07/08/11/19; J2K-02/04/05/06/08/09; RK-02 |
| Experiência durante a leitura | TB-10 a TB-13; SY-01/09/10/13/14/15; J2K-01/03/12; MH-11/14; KK-02/03 |
| Encontrar obras online | KM-01/06/10; SY-02/05/12/16/20; EH-02 |
| Catálogo misto de fontes locais/online | SY-17; KM-12; MH-05; YK-01; J2K-13 |
| Migração e correção de problemas | KM-13/14/15/16; J2K-13; RK-03/04; EH-03 |
| Download e desempenho | MH-10/11/13; KH-01/02/04; RK-01; KK-01/02 |
| Progresso e acompanhamento de leitura | KM-09; SY-04; J2K-08/09/10; MH-12 |

## Correções e verificações importantes

1. **Suporte a arquivos locais não é novidade por si só.** O Tachiyomi original já suportava coleções locais e arquivos como CBZ, CBR e EPUB. As diferenças do Tankobon são principalmente **modelo por volume**, reconhecimento de edições/omnibus e tratamento de capas.
2. **`ComicInfo.xml` não é exclusividade atual do Yōkai.** O Mihon moderno incorporou títulos/números/grupo de tradução em 2025 e datas de capítulos em 2026.
3. **Retomar downloads não é exclusividade atual do Rokku.** O Mihon documenta esse recurso em 2026, condicionado ao suporte da fonte.
4. **Múltiplos forks reutilizam código.** Komikku herda SY; Yōkai herda J2K; Rokku herda Yōkai/J2K e importa funcionalidades do Komikku. “Achei em” não significa “foi inventado por”.
5. **Fontes online podem não estar prontas após a instalação.** Nos leitores híbridos, as extensões/repositórios precisam ser configurados e as fontes podem deixar de funcionar.
6. **Documentação não é teste executado.** Para transformar uma ideia em requisito de desenvolvimento, vale verificar a implementação na versão específica e testar em mangás locais reais, incluindo arquivos incompletos e nomes irregulares.
7. **Fonte online não é requisito do Tankobon.** Ele removeu deliberadamente o ecossistema de extensões. É referência para funcionalidades de leitura local, não substituto de um leitor híbrido.

## Fontes principais e baseline

- [Tachiyomi original arquivado (README)](https://github.com/tachiyomiarchive/tachiyomi/blob/master/README.md)
- [Documentação da fonte local do Mihon](https://mihon.app/docs/guides/local-source/)
- [Tachiyomi Wiki — mangás locais](https://github.com/hXtreme/tachiyomi-wiki/blob/master/Local-manga.md)
- [Tankobon](https://github.com/mednasserallah/tankobon)
- [Komikku](https://github.com/komikku-app/komikku)
- [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY)
- [TachiyomiJ2K](https://github.com/Jays2Kings/tachiyomiJ2K)
- [Yōkai](https://github.com/null2264/yokai)
- [Mihon — changelog](https://github.com/mihonapp/mihon/blob/main/CHANGELOG.md)
- [TachiyomiEH](https://github.com/NerdNumber9/TachiyomiEH)
- [Kahon](https://github.com/AmanoTeam/Kahon)
- [Rokku](https://github.com/rokku-app/rokku)
- [Kikyo](https://github.com/achyutbadyal/kikyo)

**Fim do relatório.**
