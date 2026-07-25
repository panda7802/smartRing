"""Gunicorn entry point for the smartRing Flask application."""

from app import create_app


app = create_app()
