<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="UTF-8" />
  <title>Painel Administrativo</title>
  <script src="https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2"></script>
  <style>
    body {
      font-family: Arial, sans-serif;
      background: #111;
      color: white;
      padding: 20px;
    }

    input, button {
      padding: 8px;
      margin: 5px 0;
      width: 100%;
      max-width: 400px;
    }

    button {
      background: #e91e63;
      color: white;
      border: none;
      cursor: pointer;
    }

    button:hover {
      background: #c2185b;
    }

    .section {
      margin-bottom: 40px;
    }

    ul {
      list-style: none;
      padding: 0;
    }

    li {
      margin-bottom: 8px;
      background: #222;
      padding: 8px;
    }
  </style>
</head>
<body>

  <h1>Painel Administrativo</h1>

  <div class="section
