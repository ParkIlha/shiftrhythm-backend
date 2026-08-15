from fastapi import FastAPI
from app.routers import parse_schedule, parse_disruption, suggest_adjustment

app = FastAPI(title="shiftrhythm-ai")
app.include_router(parse_schedule.router)
app.include_router(parse_disruption.router)
app.include_router(suggest_adjustment.router)


@app.get("/")
def health():
    return {"status": "ok"}
